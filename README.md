# ejectdi-plugin
ejectdi-plugin

IntelliJ Idea Java plugin to replace Dependecy injection with static.
Example:

```java
public class StatefulClass {
 @Inject Service service;
 @Inject StatelessClass stateless;
 
 public String compute(){
    return stateless.calculate();
 }
}
```

```java
@Singleton
public class StatelessClass {
 
 public String calculate(){
    return "";
 }
}
```
Using this plugin `StatelessClass` can be converted to util class (stateless) because it doesn't have a state :

```java
public final class StatelessClass {
  private StatelessClass() {};
 
 public static String calculate(){
    return "";
 }
}
```

it will automatically refactor all usages:

```java
public class StatefulClass {
 @Inject Service service;
 
 public String compute(){
    return StatelessClass.calculate();
 }
}
```

Demo

![Demo](https://github.com/dmgcodevil/ejectdi-plugin/raw/master/demo.gif)

TODO:
- [ ] Enable refactoring feature for directories 
- [ ] Add logging info to report errors during refactoring process, currently all warnings are suppressed and not visible to the user
- [ ] Add build tool  (Maven, Gradle)
- [ ] Support recursive refactoring: A(stateful) -> B(stateful) => B(stateless) then we can try to make A stateless ?
