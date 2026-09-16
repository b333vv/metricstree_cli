package a;

/**
 * Base class fixture: fields, constructor, overridden and non-overridden methods.
 */
public class Base implements Marker {

    private final int seed;

    public Base(int seed) {
        this.seed = seed;
    }

    public int seed() {
        return seed;
    }

    @Override
    public String label() {
        return "base";
    }

    protected int scale(int factor) {
        return seed * factor;
    }
}
