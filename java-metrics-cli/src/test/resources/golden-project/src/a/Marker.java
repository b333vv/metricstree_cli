package a;

/**
 * Interface fixture: contributes to NOC, DIT and interface-related metrics.
 */
public interface Marker {

    String label();

    default boolean isMarked() {
        return true;
    }
}
