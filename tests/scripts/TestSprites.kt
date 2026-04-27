import uniffi.sprites.Point
import uniffi.sprites.Sprite
import uniffi.sprites.Sprites
import uniffi.sprites.Vector

fun main() {
    // Default position (null -> 0,0).
    Sprite(null).use { sempty ->
        check(sempty.getPosition().x == 0.0)
        check(sempty.getPosition().y == 0.0)
    }

    // Initial position + moveTo / moveBy.
    Sprite(Point(0.0, 1.0)).use { s ->
        check(s.getPosition().x == 0.0)
        check(s.getPosition().y == 1.0)

        s.moveTo(Point(1.0, 2.0))
        check(s.getPosition().x == 1.0)
        check(s.getPosition().y == 2.0)

        s.moveBy(Vector(-4.0, 2.0))
        check(s.getPosition().x == -3.0)
        check(s.getPosition().y == 4.0)
    }

    // Use-after-close raises IllegalStateException.
    val closed = Sprite(Point(0.0, 0.0))
    closed.close()
    try {
        closed.moveBy(Vector(0.0, 0.0))
        error("Should not be able to call after close")
    } catch (e: IllegalStateException) {
        // expected
    }

    // Alternate constructor via companion factory.
    Sprite.newRelativeTo(Point(0.0, 1.0), Vector(1.0, 1.5)).use { srel ->
        check(srel.getPosition().x == 1.0)
        check(srel.getPosition().y == 2.5)
    }

    // Namespace function.
    val translated = Sprites.translate(Point(1.0, 2.0), Vector(3.0, 4.0))
    check(translated.x == 4.0)
    check(translated.y == 6.0)
}
