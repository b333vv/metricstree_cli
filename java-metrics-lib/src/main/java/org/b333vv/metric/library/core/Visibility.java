package org.b333vv.metric.library.core;

/**
 * How visible a member is to code outside its own class.
 *
 * <p>The metrics that need this (AHF, MHF, and the accessor/attribute counts) treat the four Java
 * visibilities as four distinct buckets rather than as an ordered scale, so an enum is the honest
 * shape: nothing in the analysis compares two visibilities, it only groups by them.
 */
public enum Visibility {
    PRIVATE,
    PROTECTED,
    PACKAGE_PRIVATE,
    PUBLIC
}
