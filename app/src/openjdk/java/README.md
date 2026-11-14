Android has no `com.sun.net.httpserver`, which the Peergos server runs on, so it is built
here from OpenJDK's source:

    https://github.com/openjdk/jdk8u  tag jdk8u514-b00  (b661ee6ff840d6922aa7424a4d8f24760b21cf26)
    jdk/src/share/classes/com/sun/net/httpserver
    jdk/src/share/classes/sun/net/httpserver

`jdk/Exported.java` is from the same tag, `langtools/src/share/classes`.

The only change is dropping four unused imports of `sun.net.www.MessageHeader`, which Android
lacks. The files are GPLv2 with the Classpath exception, as their headers say.
