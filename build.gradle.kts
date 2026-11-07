// Hadron's root build: contractBundle (gradle/hadron-contract.gradle.kts) writes build/contract/ for blind tests.
apply(from = "gradle/hadron-contract.gradle.kts")
// regressionReplay (gradle/hadron-regressions.gradle.kts) checks every fixed bug's test still fails without its fix.
apply(from = "gradle/hadron-regressions.gradle.kts")
