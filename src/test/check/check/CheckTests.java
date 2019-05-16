package check;

class CheckTests {
  @org.junit.jupiter.api.Test
  @org.junit.jupiter.api.condition.EnabledIf({
    "print('>>')",
    "print(systemProperty)",
    "print(systemProperty.get)",
    // "print(systemProperty.get('java.version'))", // only works on the `--class-path`
    "print('<<')",
    "true"
  })
  void test() {}

  @org.junit.jupiter.api.Test
  void emitStringRepresentationOfTestModule() {
    org.junit.jupiter.api.Assumptions.assumeTrue(false, getClass().getModule().toString());
  }
}
