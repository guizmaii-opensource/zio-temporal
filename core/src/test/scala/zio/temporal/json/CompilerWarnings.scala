package zio.temporal.json

import dotty.tools.dotc.Driver
import dotty.tools.dotc.interfaces.{Diagnostic, SimpleReporter}

import java.nio.file.{Files, Path}
import scala.collection.mutable.ListBuffer

/** Drives the Scala 3 compiler in-process to capture every diagnostic — including warnings — emitted while compiling a
  * code snippet against this test run's actual classpath.
  *
  * `scala.compiletime.testing.typeCheckErrors` can't be used for this: it's a compiler intrinsic that only ever
  * surfaces errors, silently dropping warnings before returning (verified empirically — feeding it a snippet that
  * demonstrably warns under a normal `sbt compile` still returns `Nil`).
  */
object CompilerWarnings {

  final case class CapturedDiagnostic(level: Int, message: String) {
    def isWarning: Boolean = level == Diagnostic.WARNING
    def isError: Boolean   = level == Diagnostic.ERROR
  }

  /** This test run's actual classpath, so the driven compiler resolves `zio.temporal.*` / `zio.json.*` etc. exactly as
    * the enclosing sbt test run does. sbt doesn't fork tests in this build, so the context classloader IS the
    * classloader sbt built for this test run — walk it for its URLs.
    */
  private lazy val classpath: String = {
    def urlsOf(cl: ClassLoader): List[java.net.URL] = cl match {
      case null                       => Nil
      case u: java.net.URLClassLoader => u.getURLs.toList ::: urlsOf(cl.getParent)
      case other                      => urlsOf(other.getParent)
    }
    urlsOf(Thread.currentThread().getContextClassLoader).distinct
      .map(url => Path.of(url.toURI).toString)
      .mkString(java.io.File.pathSeparator)
  }

  /** Compiles `source` as a standalone `.scala` file against this test run's classpath and returns every diagnostic
    * (errors, warnings, info) the compiler emitted for it.
    */
  def diagnosticsOf(source: String): List[CapturedDiagnostic] = {
    val file   = Files.createTempFile("zio-temporal-compiler-warnings-", ".scala")
    val outDir = Files.createTempDirectory("zio-temporal-compiler-warnings-out-")
    try {
      Files.writeString(file, source)
      val captured                 = ListBuffer.empty[CapturedDiagnostic]
      val reporter: SimpleReporter = (d: Diagnostic) => { captured += CapturedDiagnostic(d.level(), d.message()); () }
      new Driver().process(
        Array("-classpath", classpath, "-d", outDir.toString, "-color:never", file.toString),
        reporter,
        null
      )
      captured.toList
    } finally {
      Files.deleteIfExists(file)
      deleteRecursively(outDir)
    }
  }

  private def deleteRecursively(dir: Path): Unit =
    if (Files.exists(dir)) {
      Files
        .walk(dir)
        .sorted(java.util.Comparator.reverseOrder())
        .forEach(Files.deleteIfExists(_))
    }
}
