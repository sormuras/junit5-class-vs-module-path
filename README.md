# junit5-class-vs-module-path
Same [tests](src/test/check/check/CheckTests.java), same binaries, different run modes.

```java
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
```

## `--module-path` and `module check`

```text
>>
org.junit.jupiter.engine.script.ScriptAccessor$SystemPropertyAccessor@4716be8b
undefined
<<
```


## `--class-path` and `unnamed module @14f9390f`

```text
>>
org.junit.jupiter.engine.script.ScriptAccessor$SystemPropertyAccessor@275bf9b3
[jdk.dynalink.beans.SimpleDynamicMethod String org.junit.jupiter.engine.script.ScriptAccessor.SystemPropertyAccessor.get(String)]
<<
```

##

At [ScriptExecutionManager.java#L85-L87](https://github.com/junit-team/junit5/blob/master/junit-jupiter-engine/src/main/java/org/junit/jupiter/engine/script/ScriptExecutionManager.java#L85-L87)
the referenced `systemProperty` variable is bound.

Why is `SimpleDynamicMethod` applied only when running on the class path?

