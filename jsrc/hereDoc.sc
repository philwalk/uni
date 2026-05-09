#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.13.3

import uni.data.HereDoc.*

/** 
 * Provides Perl-style __DATA__ / Ruby-style __END__ sections for Scala.
 * Data must be at the end of the file in a special comment block.
 */
DATA.foreach(println)

/* __DATA__
a=1
b=2
c=3
*/