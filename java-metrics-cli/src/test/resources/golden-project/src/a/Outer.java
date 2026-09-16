package a;

/**
 * Nested/inner class fixture: a static nested class and a non-static inner class.
 */
public class Outer {

    private int counter;

    public static class Nested {

        private final String name;

        public Nested(String name) {
            this.name = name;
        }

        public String name() {
            return name;
        }
    }

    public final class Inner {

        public int increment() {
            return ++counter;
        }
    }

    public Inner newInner() {
        return new Inner();
    }

    public Nested newNested() {
        return new Nested("nested");
    }
}
