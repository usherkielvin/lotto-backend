$env:JAVA_HOME = "C:\Program Files\Java\jdk-21"
$env:Path = "C:\Program Files\Java\jdk-21\bin;" + $env:Path
.\mvnw.cmd spring-boot:run
