/open PRINTING
/open https://github.com/sormuras/bach/raw/master/BUILDING

println("\n**\n**\n** M O D U L E - P A T H\n**\n**")
var ok = 0 == exe("java", "Make.java")

if (ok) {
  println("\n\n**\n**\n** C L A S S - P A T H\n**\n**");
  ok = 0 == exe("java",

      "--class-path",
      "work/test/compiled/modules/check" + File.pathSeparator + "lib/test/*" + File.pathSeparator + "lib/test-runtime-only/*",

      "org.junit.platform.console.ConsoleLauncher",

      "--scan-class-path");
}

/exit ok ? 0 : 1
