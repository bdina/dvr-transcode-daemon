package app

import org.apache.pekko.actor.typed.scaladsl.{ AbstractBehavior, ActorContext, Behaviors }
import org.apache.pekko.actor.typed.{ ActorRef, ActorSystem, Behavior }
//import org.apache.pekko.util.Timeout

import org.apache.pekko.stream._
import org.apache.pekko.stream.scaladsl._
//import org.apache.pekko.NotUsed

import scala.concurrent.duration._

import java.io.File
import java.nio.file.{Path,Paths}

import org.slf4j.{Logger,LoggerFactory}

import scala.util.{Failure,Success,Try}

object FsMonitor {
  val log = LoggerFactory.getLogger(this.getClass)

  sealed trait Command
  final case class Watch(path: Path, ext: Seq[String], replyTo: ActorRef[Ack]) extends Command

  sealed trait Response
  final case class Ack(path: Path, from: ActorRef[Watch]) extends Response

  def apply(): Behavior[Watch] = exec()

  private def exec(): Behavior[Watch] = Behaviors.receive {
    case (context, message) =>
      val worker = context.spawn(FsNotify(message.path, message.ext), "FsNotify-worker")
      context.log.info(s"[fsmonitor:exec] created new worker $worker for path ${message.path}")
      worker ! FsNotify.Poll
      Behaviors.same
  }
}

object FsNotify {
  val log = LoggerFactory.getLogger(this.getClass)

  sealed trait Command
  final case object Poll extends Command

  private final case class FsScanResponse(response: FsScan.Response) extends Command
  private final case class FsQueueResponse(response: FsQueue.Response) extends Command

  object FsQueue {
    implicit val system: ActorSystem[app.TranscodeDaemon.Command] = AppContext.system

    val bufferSize = 1000

    import java.io.File
    case class QueueProxy(
      override val context: ActorContext[QueueProxy.Request]
    , file: File
    ) extends AbstractBehavior[QueueProxy.Request](context) {
      val log = LoggerFactory.getLogger(this.getClass)

      val ffmpegResponseMapper: ActorRef[FFMpegDelegate.Response] =
        context.messageAdapter(resp => QueueProxy.FFMpegResponse(resp))

      val worker = {
        val uuid = java.util.UUID.randomUUID
        val worker = context.spawn(FFMpegDelegate(file), s"ffmpeg-delegate-${uuid}")
        context.log.info(s"[queue:proxy] send convert message to worker $worker")
        worker ! FFMpegDelegate.Request.Start(replyTo=ffmpegResponseMapper)
        worker
      }

      val killSwitch =
        KillSwitches.shared(s"queue-proxy-killswitch-${java.util.UUID.randomUUID}")
      log.info("[queue:proxy] via kill switch {}", killSwitch)
      Source
        .tick(10.second, 10.second, ())
        .via(killSwitch.flow)
        .map { case _ =>
          worker ! FFMpegDelegate.Request.Status(replyTo=ffmpegResponseMapper)
        }
        .runWith(Sink.ignore)

      var parent: Option[ActorRef[QueueProxy.Response]] = None

      def onMessage(msg: QueueProxy.Request) = msg match {
        case QueueProxy.Request.Notify(replyTo) =>
          parent = Some(replyTo)
          Behaviors.same
        case QueueProxy.FFMpegResponse(resp) =>
          context.log.info(s"[queue:proxy] received response -> $resp")
          resp match {
            case FFMpegDelegate.Response.Status(code,_) if code != 0 =>
              context.log.info(s"[queue:proxy] status -> $code")
              Behaviors.same
            case FFMpegDelegate.Response.Status(code,replyTo) =>
              context.log.info(s"[queue:proxy] signal stop -> $replyTo")
              replyTo ! FFMpegDelegate.Request.Stop(replyTo=ffmpegResponseMapper)
              parent.map(ref => ref ! QueueProxy.Response.Complete)
              killSwitch.shutdown()
              Behaviors.same
          }
      }
    }
    object QueueProxy {
      sealed trait Request
      object Request {
        case class Notify(replyTo: ActorRef[QueueProxy.Response]) extends Request
      }

      private final case class FFMpegResponse(response: FFMpegDelegate.Response) extends Request

      sealed trait Response
      object Response {
        case object Complete extends Response
        case object Pending extends Response
      }

      def apply(file: File): Behavior[QueueProxy.Request] =
        Behaviors.setup {
          case context =>
            QueueProxy(context, file)
        }
    }

    sealed trait Response
    object Response {
      case class Complete(p: Path) extends Response
    }

    case class Enqueue(p: Path, replyTo: ActorRef[FsQueue.Response])

    import org.apache.pekko.actor.typed.scaladsl.AskPattern._
    import org.apache.pekko.util.Timeout
    import scala.concurrent.Future
    val queue = Source
      .queue[FsQueue.Enqueue](bufferSize)
      .mapAsync(parallelism=1) {
        case Enqueue(p,replyTo) =>
          val uuid = java.util.UUID.randomUUID
          val proxy = system.systemActorOf(QueueProxy(p.toFile), s"ffmpeg-proxy-${uuid}")
          system.log.info(s"[queue] send convert message to proxy $proxy")

          implicit val ec: scala.concurrent.ExecutionContext = system.executionContext
          implicit val timeout: Timeout = 10.minutes
          val status: Future[QueueProxy.Response] =
            proxy.ask(ref => QueueProxy.Request.Notify(replyTo=ref))

          status.onComplete {
            case Success(QueueProxy.Response.Complete) =>
              system.log.info(s"[queue] task complete")
              replyTo ! FsQueue.Response.Complete(p)
            case Success(QueueProxy.Response.Pending) =>
              ()
            case fail =>
              ()
          }

          Future.successful(p.toFile)
      }
      .toMat(Sink.foreach(p => println(s"[queue] completed $p")))(Keep.left)
      .run()

    def +=(e: FsQueue.Enqueue): Unit = queue.offer(e)
  }

  def apply(path: Path, ext: Seq[String]): Behavior[Command] = schedule(path, ext)

  private def schedule(path: Path, ext: Seq[String]): Behavior[Command] =
    Behaviors.setup[Command] { case context =>
      val fsResponseMapper: ActorRef[FsScan.Response] =
        context.messageAdapter(resp => FsScanResponse(resp))
      val fsQueueResponseMapper: ActorRef[FsQueue.Response] =
        context.messageAdapter(resp => FsQueueResponse(resp))

      Behaviors.receive { case (context, message) =>
        message match {
          case Poll =>
            context.log.info(s"[fsnotify:schedule] received signal to start poll")
            implicit val system = AppContext.system
            Source
              .tick(10.second, 10.second, ())
              .map { case _ =>
                val uuid = java.util.UUID.randomUUID
                val worker = system.systemActorOf(FsScan(ext), s"FsScan-scan-${uuid}")
                system.log.info(s"[fsnotify:schedule] send scan message to worker $worker")
                worker ! FsScan.Scan(root=path,replyTo=fsResponseMapper)
              }
              .runWith(Sink.ignore)
            Behaviors.same
          case FsScanResponse(resp @ FsScan.Ack(paths,replyTo)) =>
            context.log.info(s"[fsnotify:schedule] received $resp message")
            paths.foreach { case path =>
              context.log.info(s"[fsnotify:schedule] put $path in queue")
              FsQueue += FsQueue.Enqueue(p=path,replyTo=fsQueueResponseMapper)
            }
            Behaviors.same
          case FsQueueResponse(resp @ FsQueue.Response.Complete(p)) =>
            context.log.info(s"[fsnotify:schedule] remove $p from cache")
            Behaviors.same
        }
      }
    }
}

object FsUtil {
  import java.nio.charset.Charset
  import java.nio.file.{Files,Path}
  import java.nio.file.attribute.{UserDefinedFileAttributeView => Xattr}
  object Attr {
    object Key {
      val FS_STATE = "fs.state"
    }
    object Value {
      val LOCK = "lock"
      val ENCODED = "encoded"
    }
    def write(path: Path, key: String, value: String): Try[Int] = Try {
      val xattr = Files.getFileAttributeView(path, classOf[Xattr])
      xattr.write(key, Charset.defaultCharset.encode(value))
    }
    def lock(path: Path): Try[Unit] =
      write(path, Key.FS_STATE, Value.LOCK).map(_ => ())
    def encoded(path: Path): Try[Unit] =
      write(path, Key.FS_STATE, Value.ENCODED).map(_ => ())
  }
}

object FsScan {
  import scala.concurrent.{ExecutionContext,Future}
  import ExecutionContext.Implicits.global

  val log = LoggerFactory.getLogger(this.getClass)

  sealed trait Command
  final case class Scan(root: Path, replyTo: ActorRef[Ack]) extends Command
  final case object Stop extends Command

  sealed trait Response
  final case class Ack(paths: Seq[Path], from: ActorRef[Scan]) extends Response

  def apply(ext: Seq[String]): Behavior[Command] = standby(ext)

  private def standby(ext: Seq[String]): Behavior[Command] = Behaviors.receive {
    case (context, message) =>
      message match {
        case Scan(root,replyTo) =>
          context.log.info(s"[standby] scan $root (replyTo $replyTo)")
          val self = context.self
          scan(root=root,ext=ext)(context.log).map {
            case result =>
              log.info(s"[standby] scanned $root with result $result - tell $replyTo")
              replyTo ! Ack(paths=result,from=self)
              self ! Stop
          }
          context.log.info(s"[standby] scan in future space")
          Behaviors.same
        case Stop =>
          context.log.info(s"[standby] shutting down")
          Behaviors.stopped
      }
  }

  private def scan(root: Path, ext: Seq[String])(implicit log: Logger): Future[Seq[Path]] = Future {
    import java.nio.file.Files
    import java.nio.file.attribute.{UserDefinedFileAttributeView => Xattr}
    import scala.jdk.CollectionConverters._
    import scala.jdk.StreamConverters._
    Files.find(root, Integer.MAX_VALUE, (_, a) => a.isRegularFile)
      .toScala(Vector)
      .filter { case path =>
        val kind = ext.find(path.getFileName.toFile.getName.endsWith(_))
        val scanned = Try {
          Files.getFileAttributeView(path, classOf[Xattr])
               .list
               .asScala
               .exists(_ == FsUtil.Attr.Key.FS_STATE)
        }.getOrElse(false)
        val hit = kind.isDefined && !scanned
        log.info(s"[scan] found $kind (state: $scanned) - hit $hit")
        hit
      }
  }
}

object AppContext {
  implicit val system: ActorSystem[TranscodeDaemon.Command] =
    ActorSystem(TranscodeDaemon(), "transcode-daemon")
}

object TranscodeDaemon extends App {
  val log = LoggerFactory.getLogger(this.getClass)

  sealed trait Command
  final case class StartWatch(path: Path) extends Command

  private final case class FsMonitorResponse(response: FsMonitor.Response) extends Command

  def apply(): Behavior[Command] = Behaviors.setup { context =>
    val responseMapper: ActorRef[FsMonitor.Response] =
      context.messageAdapter(resp => FsMonitorResponse(resp))

    Behaviors.receiveMessage[Command] {
      case StartWatch(path) =>
        val monitor = context.spawn(FsMonitor(), "monitor")
        context.log.info(s"[apply] created monitor $monitor")
        monitor ! FsMonitor.Watch(path=path,ext=Seq("ts,","mkv"),replyTo=responseMapper)
        Behaviors.same
      case FsMonitorResponse(resp) =>
        context.log.info(s"[apply] received response $resp - shutdown")
        Behaviors.stopped
    }
  }

  implicit val system: ActorSystem[app.TranscodeDaemon.Command] = AppContext.system

  val path = if (args.length > 0) args(0) else "/tmp"

  system ! StartWatch(Paths.get(path))
}

object FFMpegDelegate {
  sealed trait Request
  object Request {
    case class Status(replyTo: ActorRef[Response]) extends Request
    case class Start(replyTo: ActorRef[Response]) extends Request 
    case class Stop(replyTo: ActorRef[Response]) extends Request
  }

  sealed trait Response
  object Response {
    case class Status(code: Int,replyTo: ActorRef[Request]) extends Response
  }

  trait Error extends Exception
  object Error {
    case class DuplicateFile(f: File) extends Exception(s"duplicate file: $f") with Error
    case class IncompleteFile(f: File) extends Exception(s"incomplete file: $f") with Error
  }

  def apply(file: File): Behavior[Request] =
    Behaviors.setup(context => FFMpegDelegate(context, file))
}
case class FFMpegDelegate(
  override val context: ActorContext[FFMpegDelegate.Request]
, file: File
) extends AbstractBehavior[FFMpegDelegate.Request](context) {
  val log = LoggerFactory.getLogger(this.getClass)

  import FFMpegDelegate._
  import scala.sys.process._

  val pLog = ProcessLogger(line => log.info(line), line => log.info(line))

  def cmd(in: Path) = {
    log.info("[transcode] in - '{}'", in)
    val parent = in.getParent.toString
    val root = getBaseName(in.toFile).getOrElse("")
    log.info("[transcode] root - '{}'", root)
    val out = s"${parent}/${root}.mp4"
    val cmd = s"ffmpeg -hwaccel qsv -c:v h264_qsv -i ${in} -c:v h264_qsv -global_quality 30 ${out}"
    log.info("[transcode] command - {}", cmd)
    cmd
  }

  var proc: Option[Process] = None

  implicit val ec: scala.concurrent.ExecutionContextExecutor = scala.concurrent.ExecutionContext.global

  override def onMessage(message: Request): Behavior[Request] = message match {
    case Request.Status(sender) =>
      log.info("[transcode] received status request from {}", sender)
      val code = proc.fold (0) (p => if (!p.isAlive()) p.exitValue() else 1)
      sender ! Response.Status(code=code,replyTo=context.self)
      Behaviors.same
    case Request.Start(sender) if proc.isEmpty =>
      log.info("[transcode] request start for {}", sender)
      prepare(file) match {
        case Success(path) =>
          log.info("[transcode] convert path {}", path)
          proc = Some(cmd(path).run(pLog))
          sender ! Response.Status(code=1,replyTo=context.self)
        case other =>
          log.info("[transcode] fail wth {}", other)
          sender ! Response.Status(code=0,replyTo=context.self)
      }
      Behaviors.same
    case Request.Stop(sender) if proc.nonEmpty =>
      log.info("[transcode] received stop from {}", sender)
      proc.map(_.destroy())
      proc = None
      release(file) match {
        case Success(_) =>
          sender ! Response.Status(code=0,replyTo=context.self)
        case Failure(t) =>
          log.error("[transcode] failure - {}", t.getMessage)
          sender ! Response.Status(code=1,replyTo=context.self)
      }
      Behaviors.same
    case other =>
      log.info("[transcode] unhandled {}", other)
      Behaviors.same
  }

  def getBaseName(file: File): Option[String] = {
    log.info("[getBaseName] incoming file {}", file)
    val name = file.getName
    val result = if (!name.contains(".")) {
      None
    } else {
      val ext = name.substring(name.lastIndexOf(".") + 1)
      Some(name.substring(0, name.length - ext.length - 1))
    }
    log.info(s"[getBaseName] base name $result")
    result
  }

  val destExt = "mp4"

  def prepare(file: File): Try[Path] = {
    log.info("[prepare] incoming {}", file)
    val path = file.toPath

    val result = FsUtil.Attr.lock(path)
    log.info(s"[prepare] set state on path $path ? $result")

    val rootPath = file.getParentFile.toPath

    log.info("[prepare] root path {}", rootPath)
    val destName =
      getBaseName(file)
        .map(n => s"${n}.${destExt}")
        .getOrElse("")

    val destFile = Paths.get(rootPath.toString, destName)
    if (destFile.toFile.exists) {
      log.info(s"[prepare] destination already exists: $destFile")
      Failure(Error.DuplicateFile(file))
    } else if (file.lastModified > System.currentTimeMillis - 30000) {
      log.info(s"The file $file was modified less than 30 seconds ago. It may still be in state of being copied to this location. Skipping it for now.")
      Failure(Error.IncompleteFile(file))
    } else {
      Success(file.toPath)
    }
  }

  def release(file: File): Try[Unit] = {
    val path = file.toPath
    log.info(s"[release] drop state of $path")
    FsUtil.Attr.encoded(path)
  }
}

object EmbeddedTranscoder {
  import org.jcodec.api.transcode._
  def execute(): Unit = {
    val args = Array[String]("foo","bar")
    TranscodeMain.main(args)
  }

  import org.jcodec.api.transcode.SinkImpl
  import org.jcodec.api.transcode.SourceImpl
  import org.jcodec.api.transcode.Transcoder
  import org.jcodec.common.Codec
//  import org.jcodec.common.DemuxerTrack
  import org.jcodec.common.Format
  import org.jcodec.common.JCodecUtil
  import org.jcodec.common.Tuple.{triple,_3}
  import org.jcodec.common.TrackType

  def main(args: Array[String]): Unit = {
    import scala.jdk.CollectionConverters._

    val input = Paths.get("ts/sample.ts").toFile
    val output = Paths.get("/tmp/sample.m4v").toFile

    val inFormat = JCodecUtil.detectFormat(input)
    val demuxer = JCodecUtil.createM2TSDemuxer(input, TrackType.VIDEO)

    println(s"input video ? ${inFormat.isVideo} (demuxer ${demuxer.v0} | ${demuxer.v1})")

    val tracks = demuxer.v1.getVideoTracks.asScala
    val trackNo = tracks.foldLeft (0) { case (acc, track) =>
      val decoder = JCodecUtil.detectDecoder(track.nextFrame.getData)
      println(s"track decoder - ${decoder}")
      acc
    }

    println(s"transcode $input -> $output")
    println(s"  input ${Codec.MPEG2} | ${demuxer.v0} | ${trackNo}")

    val videoCodec: _3[Integer,Integer,Codec] = triple(demuxer.v0, trackNo, Codec.MPEG2)
    val audioCodec: _3[Integer,Integer,Codec] = triple(0, 0, Codec.AC3)
    val source = new SourceImpl(input.getAbsolutePath, inFormat/*Format.MPEG_TS*/, videoCodec, audioCodec)
    val sink = new SinkImpl(output.getAbsolutePath, Format.H264, Codec.H264, Codec.AAC)

    val builder = Transcoder.newTranscoder()
    builder.addSource(source)
    builder.addSink(sink)
//    builder.setAudioMapping(0, 0, false)
//    builder.setVideoMapping(0, 0, false)

    val transcoder = builder.create()
    transcoder.transcode()

    println("normal termination")
  }
}
