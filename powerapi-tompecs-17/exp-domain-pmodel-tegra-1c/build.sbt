libraryDependencies in ThisBuild ++= Seq(
  "org.powerapi" % "powerapi-core_2.11" % "3.4"
)

scriptClasspath ++= Seq("../conf", "../scripts")

NativePackagerKeys.executableScriptName := "powerapi"