package uni

export java.nio.file.Path
export cli.ArgsParser.*
export ext.pathExts.*
export ext.stringExts.*
export ext.helpers.*

@deprecated("Use .toSeq explicitly", "1.0.0")
given iteratorStringToSeq: Conversion[Iterator[String], Seq[String]] = _.toSeq

@deprecated("Use .toList explicitly", "1.0.0")
given iteratorStringToList: Conversion[Iterator[String], List[String]] = _.toList

@deprecated("Use .toSeq explicitly", "1.0.0")
given iteratorPathToSeq: Conversion[Iterator[Path], Seq[Path]] = _.toSeq

@deprecated("Use .toList explicitly", "1.0.0")
given iteratorPathToList: Conversion[Iterator[Path], List[Path]] = _.toList
