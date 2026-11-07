package android.graphics

/** Stands in for android.jar's stub, whose methods do nothing in a unit test. */
class Rect(
    @JvmField var left: Int = 0,
    @JvmField var top: Int = 0,
    @JvmField var right: Int = 0,
    @JvmField var bottom: Int = 0,
) {
    constructor() : this(0, 0, 0, 0)

    fun width(): Int = right - left

    fun height(): Int = bottom - top

    fun set(other: Rect) {
        left = other.left
        top = other.top
        right = other.right
        bottom = other.bottom
    }

    override fun equals(other: Any?): Boolean =
        other is Rect &&
            left == other.left &&
            top == other.top &&
            right == other.right &&
            bottom == other.bottom

    override fun hashCode(): Int = ((left * 31 + top) * 31 + right) * 31 + bottom
}
