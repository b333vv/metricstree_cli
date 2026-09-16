package a;

/**
 * Inheritance fixture: extends {@link Base} and performs a static call.
 */
public class Derived extends Base {

    private static final int OFFSET = 3;

    public Derived() {
        super(OFFSET);
    }

    @Override
    public String label() {
        return "derived:" + super.label();
    }

    public int compute(int input) {
        if (input > 0) {
            return scale(input) + Math.abs(input);
        }
        return -1;
    }
}
