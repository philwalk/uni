#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.9.2

import uni.*
val python3 = Paths.get("/ucrt64/bin/python3.exe")
printf("%s\n", python3)
printf("%s\n", python3.posx)
