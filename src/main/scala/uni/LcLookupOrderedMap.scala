package uni

final class LcLookupMap[V](private val underlying: Map[String, V]) {

  private inline def norm(k: String): String =
    k.toLowerCase(java.util.Locale.ROOT)

  def get(key: String): Option[V] =
    underlying.get(norm(key))

  def apply(key: String): V =
    underlying.apply(norm(key))

  def contains(key: String): Boolean =
    underlying.contains(norm(key))

  def getOrElse(key: String, default: => V): V =
    underlying.getOrElse(norm(key), default)

  def removed(key: String): LcLookupMap[V] =
    new LcLookupMap(underlying.removed(norm(key)))

  def iterator: Iterator[(String, V)] =
    underlying.iterator

  def keysIterator: Iterator[String] =
    underlying.keysIterator

  def keys: Iterable[String] =
    underlying.keys

  def keySet: Set[String] =
    underlying.keySet

  def valueSet: Iterable[V] =
    underlying.values

  def foreach[U](f: ((String, V)) => U): Unit =
    underlying.foreach(f)

  def collectFirst[U](pf: PartialFunction[(String, V), U]): Option[U] =
    underlying.collectFirst(pf)

  def toSeq: Seq[(String, V)] =
    underlying.toSeq

  def toMap: Map[String, V] =
    underlying

  def withFilter(p: ((String, V)) => Boolean): Iterable[(String, V)] =
    underlying.iterator.filter(p).to(Iterable)

  def nonEmpty: Boolean = underlying.nonEmpty

  def isEmpty: Boolean = underlying.isEmpty
}
