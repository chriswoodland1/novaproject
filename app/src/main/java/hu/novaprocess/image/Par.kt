package hu.novaprocess.image

import java.util.stream.IntStream

/** Soronkénti párhuzamosítás az összes CPU-magon (ForkJoin common pool). */
object Par {
    val threads: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)

    /** [0,h) sorokat tömbökre bontva, párhuzamosan: body(y0, y1) a [y0,y1) tartományra. */
    inline fun rows(h: Int, crossinline body: (Int, Int) -> Unit) {
        val chunks = (threads * 4).coerceAtMost(h.coerceAtLeast(1))
        val per = (h + chunks - 1) / chunks
        IntStream.range(0, chunks).parallel().forEach { c ->
            val y0 = c * per; val y1 = minOf(h, y0 + per)
            if (y0 < y1) body(y0, y1)
        }
    }

    /** Index-tartomány párhuzamosan (pixel-szintű ciklusokhoz). */
    inline fun range(n: Int, crossinline body: (Int, Int) -> Unit) = rows(n, body)
}
