package uni

export cli.ArgsParser.*
export pathExts.*
export stringExts.*
export helpers.*
export uni.data.{MatD, MatB, MatF, CVecD, RVecD, CVecF, RVecF, CVecB, RVecB, CVec, RVec}
export uni.io.{AggOp, JoinType}
export io.matResultOps.*

@deprecated("use 'p.lines.toSeq' if you want a Seq[String]", "uni")
given iteratorStringToSeq: Conversion[Iterator[String], Seq[String]] = _.toSeq

@deprecated("use .toList explicitly", "uni")
given iteratorStringToList: Conversion[Iterator[String], List[String]] = _.toList

@deprecated("use .toSeq explicitly", "uni")
given iteratorPathToSeq: Conversion[Iterator[Path], Seq[Path]] = _.toSeq

@deprecated("use .toList explicitly", "uni")
given iteratorPathToList: Conversion[Iterator[Path], List[Path]] = _.toList
