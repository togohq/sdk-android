plugins {
  id("com.android.library") version "9.5.1" apply false
  id("com.android.application") version "9.5.1" apply false
  id("org.jetbrains.kotlin.jvm") version "2.4.21" apply false
}

// The permissions a merged manifest declares — `uses-permission` and
// `uses-permission-sdk-23` — read one way for both gates that hold the SDK to
// INTERNET alone: the library's own manifest (`:togo:manifestPermissionCheck`)
// and what it adds to an app (`:examples:app:sdkFootprintCheck`). Two parsers
// could come to disagree about what the SDK asks for.
val declaredPermissions: (File) -> Set<String> = { manifest ->
  val ns = "http://schemas.android.com/apk/res/android"
  val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
  val document = factory.newDocumentBuilder().parse(manifest)
  listOf("uses-permission", "uses-permission-sdk-23").flatMap { tag ->
    val nodes = document.getElementsByTagName(tag)
    (0 until nodes.length).map { (nodes.item(it) as org.w3c.dom.Element).getAttributeNS(ns, "name") }
  }.toSet()
}
extra["declaredPermissions"] = declaredPermissions
