package com.phasewake.app.core

import kotlin.random.Random

/**
 * Rechenaufgabe zum Beenden des Weckers ([WakeTask.MATH]): [STEPS] Additionen zweier zweistelliger
 * Zahlen nacheinander. Eine falsche Antwort bringt eine neue Aufgabe im selben Schritt; der Fortschritt
 * geht nicht verloren.
 *
 * @param random Zufallsquelle; in Tests mit festem Startwert.
 */
class MathChallenge(private val random: Random = Random.Default, val steps: Int = STEPS) {

    /** Eine Aufgabe «[a] + [b]». */
    data class Problem(val a: Int, val b: Int) {
        val answer: Int get() = a + b
    }

    /** Wie viele Aufgaben schon gelöst sind (0 bis [steps]). */
    var solved: Int = 0
        private set

    var problem: Problem = newProblem(null)
        private set

    /** Alle Aufgaben gelöst: der Wecker darf verstummen. */
    val done: Boolean get() = solved >= steps

    /** Aktueller Schritt, beginnend bei 1 (für «Aufgabe 2 von 3»). */
    val step: Int get() = (solved + 1).coerceAtMost(steps)

    /**
     * Prüft eine Antwort. Richtig: nächster Schritt (oder fertig). Falsch: neue Aufgabe im selben Schritt.
     * Nach dem Ende ändert sich nichts mehr.
     *
     * @return ob die Antwort richtig war.
     */
    fun submit(answer: Int): Boolean {
        if (done) return true
        val correct = answer == problem.answer
        if (correct) solved++
        if (!done) problem = newProblem(problem)
        return correct
    }

    /** Neue Aufgabe, nie gleich wie die vorige. */
    private fun newProblem(previous: Problem?): Problem {
        while (true) {
            val next = Problem(random.nextInt(MIN_OPERAND, MAX_OPERAND + 1), random.nextInt(MIN_OPERAND, MAX_OPERAND + 1))
            if (next != previous) return next
        }
    }

    companion object {
        const val STEPS: Int = 3
        const val MIN_OPERAND: Int = 10
        const val MAX_OPERAND: Int = 99

        /** Höchstens so viele Ziffern hat eine Antwort (99 + 99 = 198). */
        const val MAX_DIGITS: Int = 3
    }
}
