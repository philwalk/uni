//#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation
package vast.apps

//> using dep org.vastblue:uni_3:0.13.2

import uni.*
import java.time.*
import java.time.format.DateTimeFormatter

object SourceTimestamps {
  def println(s: String = ""): Unit = print(s"$s\n") // avoid Carriage Returns
  def eprintln(s: String = ""): Unit = System.err.print(s"$s\n") // avoid Carriage Returns

  def usage(m: String = ""): Nothing = {
    showUsage(m, "",
      "<filename>    ; input",
    )
  }

  var verbose = false
  var inputFiles = Seq.empty[String]

  import scala.concurrent.ExecutionContext
  given ExecutionContext = ExecutionContext.global

  def main(args: Array[String]): Unit = {
    eachArg(args.toSeq, usage) {
    case "-v" => verbose = true
    case fname if fname.asPath.isFile =>
      inputFiles :+= fname
    case arg =>
      usage(s"unrecognized arg [$arg]")
    }

    // --- 1. Load file list (scala sources only) ---
    val scalaFiles: Seq[String] = if inputFiles.nonEmpty then
      inputFiles
    else
      //import scala.sys.process.*
      //Seq("git", "ls-files", "--", "*.sc", "*.scala").lazyLines_!
      run("c:/msys64/usr/bin/git.exe", "ls-files", "--", "*.sc") // , "*.scala").stdout


    printf("%d files\n", scalaFiles.size)
    sys.exit(3)

    // --- 2. Build lookup table of commit timestamps (scala sources only) ---
    val logLines = run("git", "log", "--name-only", "--format=%at", "--", "*.sc", "*.scala").lines

    val tsMap = scala.collection.mutable.Map.empty[String, List[Long]]
    var currentTs: Option[Long] = None

    for line <- logLines do
      if line.matches("""\d+""") then
        currentTs = Some(line.toLong)
      else if line.nonEmpty then
        val ts = currentTs.get
        tsMap.updateWith(line) {
          case Some(list) => Some(ts :: list)
          case None       => Some(List(ts))
        }

    // --- 3. Compute max filename length ---
    val maxLen = scalaFiles.map(_.length).maxOption.getOrElse(0)

    val localFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

    // --- 4. Process each file ---
    for file <- scalaFiles do
      tsMap.get(file) match
        case None =>
          eprintln(f"${file.padTo(maxLen, ' ')}:  no commit history, skipping")

        case Some(tsList) =>
          val latestGitEpoch = tsList.head
          val latestGitTime  = Instant.ofEpochSecond(latestGitEpoch)
          val gitStr         = localFmt.format(latestGitTime)

          val fsMillis = file.asPath.lastModified

          val diffSeconds = latestGitEpoch - fsMillis / 1000

          if diffSeconds < 0 then
            println(f"touch -d '$gitStr' '$file'")
  }
}