lazy val scala3 = "3.7.0"
lazy val scalaVer = scala3

lazy val supportedScalaVersions = List(scala3)

javacOptions ++= Seq("-source", "17", "-target", "17")

maxErrors := 10

// these 2 prevent everything in vast.apps from being recompiled if only 1 vast.apps source changed:
ThisBuild / incOptions := incOptions.value.withRecompileOnMacroDef(false)
ThisBuild / watchTriggeredMessage := Watch.clearScreenOnTrigger

//enablePlugins(ScalaNativePlugin)
//nativeLinkStubs := true
//ThisBuild / envFileName   := "dev.env" // sbt-dotenv plugin gets build environment here
ThisBuild / scalaVersion  := scalaVer

lazy val projectName = "uni"
ThisBuild / version       := "0.9.1" // slice machinery added but not employed
ThisBuild / versionScheme := Some("semver-spec")

ThisBuild / organization         := "org.vastblue"
ThisBuild / organizationName     := "vastblue.org"
ThisBuild / organizationHomepage := Some(url("https://vastblue.org"))

//cancelable in Global := true

ThisBuild / scmInfo := Some(
  ScmInfo(
    url(s"https://github.com/philwalk/$projectName"),
    s"scm:git@github.com:philwalk/$projectName.git"
  )
)

ThisBuild / developers.withRank(KeyRanks.Invisible) := List(
  Developer(
    id = "philwalk",
    name = "Phil Walker",
    email = "philwalk9@gmail.com",
    url = url("https://github.com/philwalk")
  )
)

// Remove all additional repository other than Maven Central from POM
ThisBuild / publishTo := {
  // For accounts created after Feb 2021 and updated after 2025:
  val nexus = "https://central.sonatype.com/"
  if (isSnapshot.value)
    Some("snapshots" at nexus + "content/repositories/snapshots")
  else
    Some("releases" at nexus + "service/local/staging/deploy/maven2")
}

ThisBuild / publishMavenStyle.withRank(KeyRanks.Invisible) := true

ThisBuild / crossScalaVersions := supportedScalaVersions

// For all Sonatype accounts created on or after February 2021
ThisBuild / sonatypeCredentialHost := "https://central.sonatype.com/"

resolvers += Resolver.mavenLocal

publishTo := sonatypePublishToBundle.value

Compile / packageBin / packageOptions +=
  Package.ManifestAttributes(java.util.jar.Attributes.Name.CLASS_PATH -> "")

lazy val root = (project in file(".")).
  enablePlugins(BuildInfoPlugin).
  settings(
    parallelExecution  := false,
    crossScalaVersions := supportedScalaVersions,
    name               := projectName,
    description        := "Support for expressive scripting",
 // mainClass          := Some("vast.apps.ShowSysProps"),
    buildInfoKeys      := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion),
    buildInfoPackage   := "uni", // available as "import uni.BuildInfo"
  )

libraryDependencies ++= Seq(
  "org.bytedeco"             % "openblas-platform" % "0.3.30-1.5.12",
  "org.scalameta"           %% "munit"             % "1.2.1" % Test,
  "org.scalameta"           %% "munit-scalacheck"  % "1.2.0" % Test,
)

/*
 * build.sbt
 * SemanticDB is enabled for all sub-projects via ThisBuild scope.
 * https://www.scala-sbt.org/1.x/docs/sbt-1.3-Release-Notes.html#SemanticDB+support
 */
inThisBuild(
  List(
    scalaVersion := scalaVersion.value, // 2.13.12, or 3.x
    // semanticdbEnabled := true     // enable SemanticDB
    // semanticdbVersion := scalafixSemanticdb.revision // only required for Scala 2.x
  )
)

scalacOptions := {
  Seq(
    // "-Xmaxerrs", "10",
    "-encoding",
    "utf-8",
    "-explaintypes",
    "-language:existentials",
    "-language:experimental.macros",
    "-language:higherKinds",
    "-language:implicitConversions",
    "-deprecation",
    "-feature",
    "-Wunused:imports",
    "-Wunused:locals",
    "-Wunused:privates",
    // Linting options
    "-unchecked"
  )
}
scalacOptions ++= (CrossVersion.partialVersion(scalaVersion.value) match {
case Some((2, n)) if n >= 13 =>
  Seq(
    "-Ytasty-reader",
    "-Xsource:3",
    "-Xmaxerrs",
    "10",
    "-Xsource:3",
    "-Xsource-features:implicit-resolution",
    "-Yscala3-implicit-resolution",
    "-language:implicitConversions",
  )
case _ =>
  Nil
})

// key identifier, otherwise this field is ignored; passwords supplied by pinentry
credentials += Credentials(
  "GnuPG Key ID",
  "gpg",
  "1CF370113B7EE5A327DD25E7B5D88C95FC9CB6CA", // key identifier
  "ignored",
)

credentials += Credentials(Path.userHome / ".sonatype_credentials") 

// Set this to the same value set as your credential files host.
sonatypeCredentialHost := "central.sonatype.com"
sonatypeRepository := "https://central.sonatype.com/service/local"


