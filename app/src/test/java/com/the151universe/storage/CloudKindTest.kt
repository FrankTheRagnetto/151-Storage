package com.the151universe.storage

import org.junit.Assert.assertEquals
import org.junit.Test

/**: i formati del server che il Cloud deve trattare come foto o video. */
class CloudKindTest {
    @Test fun lAnteprimaGoProEUnVideo() {
        assertEquals("video", CloudList.kindOf("GL011190.LRV"))
        assertEquals("video", CloudList.kindOf("clip.mp4"))
        assertEquals("image", CloudList.kindOf("IMG_1.HEIC"))
        assertEquals(null, CloudList.kindOf("note.txt"))
    }
}
