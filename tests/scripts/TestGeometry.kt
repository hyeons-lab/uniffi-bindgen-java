import uniffi.geometry.Geometry
import uniffi.geometry.Line
import uniffi.geometry.Point

fun main() {
    val ln1 = Line(Point(0.0, 0.0), Point(1.0, 2.0))
    val ln2 = Line(Point(1.0, 1.0), Point(2.0, 2.0))

    check(Geometry.gradient(ln1) == 2.0)
    check(Geometry.gradient(ln2) == 1.0)

    check(Geometry.intersection(ln1, ln2) == Point(0.0, 0.0))
    check(Geometry.intersection(ln1, ln1) == null)
}
