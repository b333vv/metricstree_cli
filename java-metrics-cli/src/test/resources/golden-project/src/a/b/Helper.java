package a.b;

import a.Base;
import a.Marker;

/**
 * Second package fixture (package {@code a.b}) depending on package {@code a}.
 */
public class Helper implements Marker {

    private final Base base = new Base(7);

    @Override
    public String label() {
        return "helper";
    }

    public int combine(int input) {
        return base.seed() + input;
    }
}
