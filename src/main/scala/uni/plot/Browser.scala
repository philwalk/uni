package uni.plot

import java.nio.file.Path

/**
 * How a shown chart reaches the screen. The default is a **standalone window**: the
 * user's default HTML browser, launched in app mode when it is a Chromium-family browser
 * (`--app=<file-url>`, sized to the chart) — no tabs, no address bar, its own taskbar
 * entry — or with `-new-window` when it is Firefox. The default browser is read from the
 * OS (Windows: the `https` URL association in the registry; macOS: LaunchServices' handler
 * for `http`, launched through the bundle's own executable so a running browser still gets
 * the flags; Linux: `xdg-settings get default-web-browser`), so a machine whose default is
 * Chrome or Firefox gets that browser, and Edge only when it is the default or nothing
 * else can be found. When the default cannot be read, the PATH is probed — Chrome,
 * Chromium, Brave, Firefox, then Edge last.
 *
 * Overrides, all environment variables (the same names in the Rust crate):
 *   - `UNI_PLOT_WINDOW=tab` — a tab in the default browser via the OS opener, the plain
 *     `rundll32 url.dll,FileProtocolHandler` / `open` / `xdg-open` route (`app`, the default,
 *     is the window described above);
 *   - `UNI_PLOT_BROWSER=<name-or-path>` — use this browser (its family is inferred from the
 *     name: `chrome`, `chromium`, `edge`, `brave`, `vivaldi`, `opera` → app window;
 *     `firefox`, `librewolf`, `waterfox` → new window; anything else → plain open);
 *   - `UNI_PLOT_NO_OPEN` — open nothing; print the page's path (headless runs, CI).
 * Any launch that fails falls through to the next route, and finally to printing the path,
 * so `show` never throws.
 */
private[plot] object Browser:

  /** The command prefix and the family that decides its arguments. */
  final case class Choice(cmd: Seq[String], family: String)

  private val chromiumNames = Seq("chrome", "chromium", "msedge", "edge", "brave", "vivaldi", "opera")
  private val firefoxNames  = Seq("firefox", "librewolf", "waterfox")

  def family(name: String): String =
    val n = name.toLowerCase
    if chromiumNames.exists(n.contains) then "chromium"
    else if firefoxNames.exists(n.contains) then "firefox"
    else "other"

  private def os: String = sys.props.getOrElse("os.name", "").toLowerCase
  private def isWin: Boolean = os.contains("win")
  private def isMac: Boolean = os.contains("mac")

  private def quiet(cmd: String*): Seq[String] =
    try uni.run(cmd*).lines
    catch case scala.util.control.NonFatal(_) => Seq.empty

  // ── the default HTML browser, per OS ──────────────────────────────────────

  private def defaultWindows(): Option[Choice] =
    val progId = quiet("reg", "query",
        """HKCU\Software\Microsoft\Windows\Shell\Associations\UrlAssociations\https\UserChoice""", "/v", "ProgId")
      .collectFirst { case l if l.trim.startsWith("ProgId") => l.trim.split("\\s+").last }
    progId.flatMap { id =>
      quiet("reg", "query", s"""HKCR\\$id\\shell\\open\\command""", "/ve")
        .collectFirst { case l if l.contains("REG_SZ") => l.substring(l.indexOf("REG_SZ") + 6).trim }
        .flatMap { command =>
          // `"C:\...\chrome.exe" --single-argument %1` — the exe is the first quoted token
          val exe = if command.startsWith("\"") then command.drop(1).takeWhile(_ != '"') else command.takeWhile(_ != ' ')
          if exe.nonEmpty then Some(Choice(Seq(exe), family(exe))) else None
        }
    }

  private def defaultMac(): Option[Choice] =
    val plist = sys.props("user.home") + "/Library/Preferences/com.apple.LaunchServices/com.apple.launchservices.secure.plist"
    val json  = quiet("plutil", "-convert", "json", "-o", "-", plist).mkString
    val bundle = json.split("\\},\\s*\\{").iterator
      .filter(seg => seg.contains("\"LSHandlerURLScheme\":\"http\"") || seg.contains("\"LSHandlerURLScheme\":\"https\""))
      .flatMap(seg => "\"LSHandlerRoleAll\"\\s*:\\s*\"([^\"]+)\"".r.findFirstMatchIn(seg).map(_.group(1)))
      .nextOption()
    bundle.map { b =>
      // The bundle's own executable, so `--app`/`-new-window` reach a browser that is
      // already running (`open -b … --args` passes arguments only to a new instance).
      val binary =
        for
          app  <- quiet("osascript", "-e", s"""POSIX path of (path to application id "$b")""").headOption.map(_.trim).filter(_.nonEmpty)
          name <- quiet("defaults", "read", s"${app.stripSuffix("/")}/Contents/Info", "CFBundleExecutable").headOption.map(_.trim).filter(_.nonEmpty)
          bin   = s"${app.stripSuffix("/")}/Contents/MacOS/$name"
          if java.nio.file.Files.isExecutable(uni.Paths.get(bin))
        yield bin
      Choice(binary.map(Seq(_)).getOrElse(Seq("open", "-b", b, "--args")), family(b))
    }

  private def defaultLinux(): Option[Choice] =
    quiet("xdg-settings", "get", "default-web-browser").headOption.map(_.trim).filter(_.nonEmpty).map { desktop =>
      val name = desktop.stripSuffix(".desktop")
      val dirs = Seq(sys.props("user.home") + "/.local/share/applications", "/usr/local/share/applications", "/usr/share/applications")
      val exec = dirs.map(d => uni.Paths.get(s"$d/$desktop")).find(p => java.nio.file.Files.isRegularFile(p))
        .flatMap(p => scala.io.Source.fromFile(p.toFile).getLines().find(_.startsWith("Exec=")))
        .map(_.stripPrefix("Exec=").trim.split("\\s+").head)
      Choice(Seq(exec.getOrElse(name)), family(name))
    }

  def defaultBrowser(): Option[Choice] =
    try
      if isWin then defaultWindows() else if isMac then defaultMac() else defaultLinux()
    catch case scala.util.control.NonFatal(_) => None

  /** Windows registers installed browsers under `App Paths` even when they are not on the
   *  PATH — the way `chrome` resolves from a Run dialog. */
  private def appPath(name: String): Option[String] =
    if !isWin then None
    else
      val key = """HKLM\SOFTWARE\Microsoft\Windows\CurrentVersion\App Paths\""" + name.stripSuffix(".exe") + ".exe"
      quiet("reg", "query", key, "/ve")
        .collectFirst { case l if l.contains("REG_SZ") => l.substring(l.indexOf("REG_SZ") + 6).trim.stripPrefix("\"").stripSuffix("\"") }
        .filter(_.nonEmpty)

  /** A program name → its executable: the PATH, then (Windows) App Paths. */
  private def resolve(name: String): Option[String] =
    if name.contains("/") || name.contains(java.io.File.separator) then Some(name)
    else uni.whereInPath(name).orElse(appPath(name))

  /** Probe when the OS default cannot be read — Edge deliberately last. */
  private val candidates = Seq("google-chrome", "chrome", "chromium", "chromium-browser", "brave-browser", "brave", "firefox", "msedge")

  private def probe(): Option[Choice] =
    candidates.iterator.flatMap(n => resolve(n).map(p => Choice(Seq(p), family(n)))).nextOption()

  private def fromEnv(): Option[Choice] =
    sys.env.get("UNI_PLOT_BROWSER").map(_.trim).filter(_.nonEmpty).map { b =>
      Choice(Seq(resolve(b).getOrElse(b)), family(b))
    }

  // ── launching ─────────────────────────────────────────────────────────────

  private def start(cmd: Seq[String]): Boolean =
    try
      new ProcessBuilder(cmd*).redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
      true
    catch case scala.util.control.NonFatal(_) => false

  /** The OS opener: a tab in the default browser. */
  def openTab(path: Path): Boolean =
    val p = path.toAbsolutePath.toString
    if isWin then start(Seq("rundll32", "url.dll,FileProtocolHandler", p))
    else if isMac then start(Seq("open", p))
    else start(Seq("xdg-open", p)) || start(Seq("wslview", p))

  private def fileUrl(path: Path): String = path.toAbsolutePath.toUri.toString

  /** A standalone window sized to the chart, per the rules above; `false` when nothing
   *  could be started (the caller then prints the path). */
  def show(path: Path, width: Int, height: Int): Boolean =
    if sys.env.get("UNI_PLOT_WINDOW").exists(_.trim.equalsIgnoreCase("tab")) then openTab(path)
    else
      val url = fileUrl(path)
      val choice = fromEnv().orElse(defaultBrowser()).orElse(probe())
      val launched = choice.exists { c =>
        c.family match
          case "chromium" => start(c.cmd ++ Seq(s"--app=$url", s"--window-size=${width + 16},${height + 40}"))
          case "firefox"  => start(c.cmd ++ Seq("-new-window", url))
          case _          => false
      }
      launched || openTab(path)
