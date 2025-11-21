import BuildConfig.*

import scala.collection.Seq

Global / onChangedBuildSource := ReloadOnSourceChanges

val scala3 = "3.3.7"

ThisBuild / organization  := "com.guizmaii"
ThisBuild / versionScheme := Some("early-semver")

val publishSettings = Seq(
  organizationHomepage := Some(url("https://vhonta.dev")),
  homepage             := Some(url("https://zio-temporal.vhonta.dev")),
  licenses             := Seq(
    "Apache 2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")
  ),
  scmInfo := Some(
    ScmInfo(
      url(s"https://github.com/vitaliihonta/zio-temporal"),
      s"scm:git:https://github.com/vitaliihonta/zio-temporal.git",
      Some(s"scm:git:git@github.com:vitaliihonta/zio-temporal.git")
    )
  ),
  developers := List(
    Developer(
      id = "vitaliihonta",
      name = "Vitalii Honta",
      email = "vitalii.honta@gmail.com",
      url = url("https://github.com/vitaliihonta")
    )
  )
)

lazy val coverageSettings = Seq(
  coverageExcludedPackages := "com\\.example\\..*;.*\\.JavaTypeTag;.*\\.JavaTypeTag\\..*;.*\\.TypeIsSpecified;" +
    "zio\\.temporal\\.internal\\.*Macro*;" +
    "zio\\.temporal\\.activity\\.ZLocalActivityStubBuilder*;" +
    "zio\\.temporal\\.activity\\.ZLocalStubBuilder*;" +
    "zio\\.temporal\\.workflow\\.ZWorkflowStubBuilder*;" +
    "zio\\.temporal\\.workflow\\.ZChildWorkflowStubBuilder*;" +
    "zio\\.temporal\\.workflow\\.ZWorkflowContinueAsNewStubBuilder*;"
)

lazy val baseProjectSettings = Seq(
  scalaVersion       := scala3,
  crossScalaVersions := List(scala3),
  scalacOptions ++= Seq(
    "-language:implicitConversions",
    "-language:higherKinds",
    "-deprecation"
  ),
  scalacOptions ++= Seq("-no-indent"),             // See https://x.com/ghostdogpr/status/1706589471469425074
  scalacOptions ++= Seq("-language:noAutoTupling") // See https://github.com/scala/scala3/discussions/19255
)

val baseSettings    = baseProjectSettings ++ coverageSettings
val baseLibSettings = baseSettings ++ publishSettings

lazy val root = project
  .in(file("."))
  .settings(baseSettings, noPublishSettings, unidocSettings)
  .settings(
    crossScalaVersions := Nil
  ) // https://www.scala-sbt.org/1.x/docs/Cross-Build.html#Cross+building+a+project+statefully,
  .settings(
    name         := "zio-temporal-root",
    scalaVersion := scala3
  )
  .aggregate(
    core,
    testkit,
    protobuf,
    `integration-tests`,
    docs
  )
  .enablePlugins(ScalaUnidocPlugin)

lazy val docs = project
  .in(file("docs"))
  .settings(
    name := "zio-temporal-docs",
    baseSettings,
    mdocSettings,
    noPublishSettings,
    scalaVersion := scala3,
    libraryDependencies ++= docsLibs
  )
  .dependsOn(
    core,
    protobuf,
    testkit
  )
  .enablePlugins(MdocPlugin, DocusaurusPlugin)

lazy val core = project
  .in(file("core"))
  .settings(baseLibSettings)
  .enablePlugins(BuildInfoPlugin)
  .settings(
    name := "zio-temporal-core",
    libraryDependencies ++= coreLibs,
    buildInfoKeys := Seq[BuildInfoKey](
      organization,
      BuildInfoKey.map(name) { case (k, _) => k -> "zio-temporal" },
      version,
      scalaVersion,
      isSnapshot
    ),
    buildInfoOptions += BuildInfoOption.ToMap,
    buildInfoPackage := "zio.temporal.build"
  )

lazy val testkit = project
  .in(file("testkit"))
  .dependsOn(core)
  .settings(baseLibSettings)
  .settings(
    name := "zio-temporal-testkit",
    libraryDependencies ++= testkitLibs
  )

lazy val `integration-tests` = project
  .in(file("integration-tests"))
  .dependsOn(
    core,
    testkit % "compile->compile;test->test",
    protobuf
  )
  .settings(baseSettings, noPublishSettings)
  .settings(
    libraryDependencies ++= testLibs,
    testFrameworks ++= Zio.testFrameworks,
    (Test / parallelExecution) := false,
    Compile / PB.targets       := Seq(
      scalapb.gen(
        flatPackage = true,
        grpc = false
      ) -> (Compile / sourceManaged).value / "scalapb"
    )
  )

lazy val protobuf = project
  .in(file("protobuf"))
  .settings(baseLibSettings)
  .dependsOn(core)
  .settings(
    name := "zio-temporal-protobuf",
    libraryDependencies ++= protobufLibs,
    Compile / PB.targets := Seq(
      scalapb.gen(
        flatPackage = true,
        grpc = false
      ) -> (Compile / sourceManaged).value / "scalapb"
    )
  )

lazy val examples = project
  .in(file("examples"))
  .settings(baseSettings, noPublishSettings)
  .settings(
    coverageEnabled      := false,
    Compile / PB.targets := Seq(
      scalapb.gen(
        flatPackage = true,
        grpc = false
      ) -> (Compile / sourceManaged).value / "scalapb"
    ),
    libraryDependencies ++= examplesLibs
  )
  .dependsOn(
    core,
    testkit,
    protobuf
  )

// MDOC
lazy val mdocSettings = Seq(
  mdocIn        := file("docs/src/main/mdoc"),
  mdocVariables := Map(
    "VERSION"      -> version.value,
    "ORGANIZATION" -> organization.value,
    "EMAIL"        -> developers.value.head.email
  ),
  crossScalaVersions := Seq(scalaVersion.value)
)

lazy val unidocSettings = Seq(
  cleanFiles += (ScalaUnidoc / unidoc / target).value,
  ScalaUnidoc / unidoc / unidocProjectFilter := inProjects(core, protobuf, testkit),
  ScalaUnidoc / unidoc / target              := (LocalRootProject / baseDirectory).value / "website" / "static" / "api",
  ScalaUnidoc / unidoc / scalacOptions ++= Seq(
    "-sourcepath",
    (LocalRootProject / baseDirectory).value.getAbsolutePath,
    "-doc-title",
    "ZIO Temporal",
    "-doc-version",
    s"v${version.value}"
  )
)

val updateSiteVariables = taskKey[Unit]("Update site variables")
ThisBuild / updateSiteVariables := {
  val file =
    (ThisBuild / baseDirectory).value / "website" / "variables.js"

  val variables =
    Map[String, String](
      "organization"           -> (ThisBuild / organization).value,
      "latestVersion"          -> version.value,
      "downloadReportsBaseUrl" -> "https://zio-temporal.vhonta.dev/assets"
    )

  val fileHeader =
    "// Generated by sbt. Do not edit directly."

  val fileContents =
    variables.toList
      .sortBy { case (key, _) => key }
      .map { case (key, value) => s"  $key: '$value'" }
      .mkString(s"$fileHeader\nmodule.exports = {\n", ",\n", "\n};\n")

  IO.write(file, fileContents)
}

// MISC
lazy val noPublishSettings =
  publishSettings ++ Seq(
    publish / skip  := true,
    publishArtifact := false
  )
