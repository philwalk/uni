#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using scala 3.7.2
//> using dep org.vastblue:uni_3:0.22.1

// THIN BY DESIGN — do not restore the full source here.
//
// The simulator lives in `src/main/scala/apps/MarketSim.scala` and ships inside uni_3.
//
// WHY.  The emitted sidecar stamps `uni.BuildInfo.version`, which describes the JAR.  While the
// model also existed here as a loose script, an edited copy of it produced a sidecar claiming a
// released version — the stamp and the code came from different artifacts.  With the model reachable
// only through the jar, the two are one artifact, which is the property the Rust twin already had by
// shipping its example inside the crate.  The only meaningful edit left in this file is the `using
// dep` version, and that moves the code and the reported version TOGETHER.
//
// RESIDUAL, stated so no one reads more into the stamp than it carries: `sbt publishLocal` stamps
// whatever `version` says in build.sbt, so an edited `MarketSim.scala` published locally still
// yields a jar reporting that version, and this file resolves the local cache first.  That is the
// same residual as `cargo build` over an edited `market_sim.rs` — symmetric with the Rust twin, not
// free of it.  A version field is an assertion by the producer, never a proof; the sidecar's `world`
// block and its gate fidelity ratios are what actually pin behaviour.

uni.apps.MarketSim.main(args)
