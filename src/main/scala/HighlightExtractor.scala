package cchantep

import sbt._
import sbt.Keys._

import sbt.plugins.JvmPlugin

object HighlightExtractorPlugin extends AutoPlugin {
  import scala.collection.JavaConverters._
  import org.apache.commons.io.FileUtils
  import org.apache.commons.io.filefilter.{ IOFileFilter, TrueFileFilter }

  override def trigger = allRequirements
  override def requires = JvmPlugin

  object autoImport {
    sealed trait HLActivation

    case object HLEnabledByDefault extends HLActivation {
      override val toString = "<hl-enabled-by-default>"
    }

    case class HLEnabledBySysProp(propName: String) extends HLActivation {
      override val toString = s"<hl-enabled-by-system-property: $propName>"
    }

    /** Enabled by default, but disabled in case of system property. */
    case class HLDisabledBySysProp(propName: String) extends HLActivation {
      override val toString = s"<hl-disabled-by-system-property: $propName>"
    }

    val highlightStartToken = SettingKey[String]("highlightStartToken",
      """Token indicating a highlight starts; default: "```scala"""")

    val highlightEndToken = SettingKey[String]("highlightEndToken",
      """Token indicating a highlight is ended; default: "```"""")

    val highlightDirectory = SettingKey[File]("highlightDirectory",
      """Directory to be scanned; default: baseDirectory""")

    val highlightActivation = SettingKey[HLActivation]("highlightActivation",
      """Activation of the highlight compiler; default: activated""")
  }

  import autoImport._

  import SbtCompat.{ WatchSource => Src }

  val markdownSources = SettingKey[Seq[Src]]("highlightMarkdownSources")

  override def projectSettings = Seq(
    highlightStartToken := "```scala",
    highlightEndToken := "```",
    highlightDirectory := baseDirectory.value,
    highlightActivation := HLEnabledByDefault,
    includeFilter in doc := "*.md",
    mappings in (Test, packageBin) ~= {
      (_: Seq[(File, String)]).filter {
        case (_, target) => !target.startsWith("highlightextractor/")
      }
    },
    scalacOptions in (Test, doc) ++= activated(highlightActivation.value,
      List.empty[String]) { List("-skip-packages", "highlightextractor") },
    markdownSources := activated(highlightActivation.value, Seq.empty[Src]) {
      SbtCompat.markdownSources.value
    },
    watchSources in Test := activated(
      highlightActivation.value, watchSources.value) {
      markdownSources.value
    },
    sourceGenerators in Test += (Def.task {
      val log = streams.value.log
      val src = SbtCompat.markdownFiles.value

      val st = (highlightStartToken in ThisBuild).
        or(highlightStartToken).value

      val et = (highlightEndToken in ThisBuild).
        or(highlightEndToken).value

      if (!autoScalaLibrary.value) {
        log.warn(s"Skip highlight extraction on non-Scala project: ${thisProject.value.id}")
        Seq.empty
      } else activated(highlightActivation.value, Seq.empty[File]) {
        val out = (sourceManaged in Test).value

        new HighlightExtractor(src, out, st, et, log).apply()
      }
    }).taskValue
  )

  // ---

  private def activated[T](setting: HLActivation, default: T)(f: => T): T =
    setting match {
      case HLEnabledBySysProp(p) if sys.props.get(p).isDefined => f
      case HLDisabledBySysProp(p) if !sys.props.get(p).isDefined => f
      case HLEnabledByDefault => f
      case _ => default
    }

  private[cchantep] def listFiles(
    dir: File,
    includeFilter: FileFilter,
    excludeFilter: FileFilter
  ): Seq[File] = {
    val excludes = excludeFilter.accept(_)
    val iofilter = new IOFileFilter {
      def accept(f: File) = includeFilter.accept(f)
      def accept(d: File, n: String) = !excludes(d) && accept(d / n)
    }
    val dirfilter = new IOFileFilter {
      def accept(f: File) = !excludes(f)
      def accept(d: File, n: String) = accept(d / n)
    }

    FileUtils.listFiles(dir, iofilter, dirfilter).
      asScala.filterNot(excludes).toSeq
  }
}

final class HighlightExtractor(
  sources: Seq[File],
  out: File,
  startToken: String,
  endToken: String,
  log: Logger) {

  import java.io.PrintWriter

  private def generateFile(lines: Iterator[String], out: PrintWriter, ln: Long): (Iterator[String], Long) = if (!lines.hasNext) (lines -> ln) else {
    val line = lines.next()

    if (line == endToken) (lines -> (ln + 1)) else {
      out.println(line)
      out.flush()

      generateFile(lines, out, ln + 1)
    }
  }

  private def generate(out: File)(input: File, generated: Seq[File], samples: Seq[String], lines: Iterator[String], ln: Long, pkgi: Long): (Seq[File], Seq[String]) =
    if (!lines.hasNext) (generated -> samples) else {
      val line = lines.next()

      if (line contains startToken) {
        val n = generated.size
        val in = input.getName.replaceAll("\\.", "-")
        val sn = s"$in-$ln-$n.scala"
        val f = out / sn
        lazy val p = new PrintWriter(new java.io.FileOutputStream(f))
        val first = lines.next()
        val pkg = first startsWith "package "

        log.debug(s"Generating the sample #$n ($sn) ...")

        try {
          if (!pkg) p.println(
            s"package highlightextractor.samples$pkgi\n\ntrait Sample$n {")

          p.println(s"// File '${input.getAbsolutePath}', line ${ln + 1}\n")

          val (rem, no) = generateFile(Iterator(first) ++ lines, p, ln + 1L)

          if (!pkg) p.println("\n}")
          p.println(s"// end of sample #$n")

          p.flush()

          val sa = if (pkg) samples else samples :+ s"Sample$n"

          generate(out)(input, generated :+ f, sa, rem, no, pkgi)
        } finally { p.close() }
      } else generate(out)(input, generated, samples, lines, ln + 1, pkgi)
    }

  private def genPkg(out: File, i: Long, samples: Seq[String]): File = {
    val pkgf = out / s"package$i.scala"
    lazy val pkgout = new PrintWriter(new java.io.FileOutputStream(pkgf))

    try {
      pkgout.print(s"package highlightextractor\r\npackage object samples$i")

      samples.headOption.foreach { n =>
        pkgout.print(s"\n  extends $n")
      }

      samples.drop(1).foreach { n =>
        pkgout.print(s"\n  with $n")
      }

      pkgout.println(" { }")
      pkgout.flush()
    } finally {
      pkgout.close()
    }

    pkgf
  }

  def apply(): Seq[File] = {
    out.mkdirs()
    val gen = generate(out) _
    sources.foldLeft(Seq.empty[File] -> Seq.empty[String]) {
      case ((generated, samples), f) => {
        log.info(s"Processing $f ...")

        val pi = Math.abs(this.hashCode * (generated.size + 1))
        val (g, s) = gen(f, generated, Nil,
          scala.io.Source.fromFile(f).getLines, 1L, pi)
        val pf = genPkg(out, pi, s)

        (g :+ pf) -> (samples ++ s)
      }
    }._1
  }
}
