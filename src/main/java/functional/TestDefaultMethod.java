package functional;

public class TestDefaultMethod implements Interface1, Interface2 {
    public static void main(String[] args) {
        TestDefaultMethod m = new TestDefaultMethod();
        m.foo();
        m.bar();
    }

    @Override
    public void foo() {
        Interface1.super.foo();
    }
}

interface Interface1 {
    default void foo() {
        System.out.println("Foo in interface 1");
    }
}

interface Interface2 {
    default void foo() {
        System.out.println("Foo in interface 2");
    }

    default void bar() {
        System.out.println("Bar in interface 2");
    }
}