package com.pixlory.color.by.number.highlight

/** Keeps a tapped target visible until its animated fill has committed its final pixels. */
internal object HighlightTargetResolver {
    fun resolve(
        requestedTargets: IntArray,
        retainedDuringFill: Set<Int>,
        completedTargets: Set<Int>,
    ): IntArray = (requestedTargets.asSequence() + retainedDuringFill.asSequence())
        .filterNot(completedTargets::contains)
        .distinct()
        .sorted()
        .toList()
        .toIntArray()
}
