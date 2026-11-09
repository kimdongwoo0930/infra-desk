package com.infradesk.ui.files;

import com.infradesk.ui.files.TextFileCodec.NotEditableException;
import com.infradesk.ui.files.TextFileCodec.Problem;

import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextFileCodecTest {

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** 읽었다가 그대로 되돌린 결과가 원본과 같아야 한다(파일을 망가뜨리지 않는다). */
    private static void assertRoundTrip(byte[] original) throws NotEditableException {
        TextFileCodec.Decoded d = TextFileCodec.decode(original);
        assertArrayEquals(original, TextFileCodec.encode(d.text(), d.crlf(), d.bom()));
    }

    @Test
    void LF_파일은_그대로_돌아온다() throws Exception {
        byte[] data = utf8("server {\n  listen 80;\n}\n");
        TextFileCodec.Decoded d = TextFileCodec.decode(data);

        assertEquals("server {\n  listen 80;\n}\n", d.text());
        assertFalse(d.crlf());
        assertFalse(d.bom());
        assertFalse(d.mixedEndings());
        assertRoundTrip(data);
    }

    @Test
    void CRLF_파일은_편집_창에서는_LF로_보이고_저장하면_CRLF로_돌아온다() throws Exception {
        byte[] data = utf8("a\r\nb\r\n");
        TextFileCodec.Decoded d = TextFileCodec.decode(data);

        assertEquals("a\nb\n", d.text());
        assertTrue(d.crlf());
        assertFalse(d.mixedEndings());
        assertRoundTrip(data);
    }

    @Test
    void UTF8_BOM은_기억했다가_다시_붙인다() throws Exception {
        byte[] data = new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'h', 'i'};
        TextFileCodec.Decoded d = TextFileCodec.decode(data);

        assertEquals("hi", d.text(), "BOM이 글자로 보이면 안 된다");
        assertTrue(d.bom());
        assertRoundTrip(data);
    }

    @Test
    void 한글과_끝_개행_유무가_그대로다() throws Exception {
        assertRoundTrip(utf8("안녕하세요 서버\n두 번째 줄"));   // 끝 개행 없음
        assertRoundTrip(utf8("안녕하세요 서버\n두 번째 줄\n"));
    }

    @Test
    void 빈_파일도_열린다() throws Exception {
        TextFileCodec.Decoded d = TextFileCodec.decode(new byte[0]);

        assertEquals("", d.text());
        assertRoundTrip(new byte[0]);
    }

    @Test
    void 줄바꿈이_섞여_있으면_표시하고_더_많은_쪽으로_통일한다() throws Exception {
        TextFileCodec.Decoded d = TextFileCodec.decode(utf8("a\r\nb\r\nc\n"));

        assertTrue(d.mixedEndings());
        assertTrue(d.crlf(), "CRLF가 더 많다");
        assertEquals("a\nb\nc\n", d.text());
    }

    @Test
    void 바이너리는_거절한다() {
        NotEditableException e = assertThrows(NotEditableException.class,
                () -> TextFileCodec.decode(new byte[] {'P', 'N', 'G', 0, 1, 2}));

        assertEquals(Problem.BINARY, e.problem());
    }

    @Test
    void UTF8이_아닌_글자는_거절한다() {
        byte[] eucKr = "안녕".getBytes(Charset.forName("EUC-KR"));

        NotEditableException e = assertThrows(NotEditableException.class, () -> TextFileCodec.decode(eucKr));

        assertEquals(Problem.NOT_UTF8, e.problem());
    }

    @Test
    void CR만_있는_줄바꿈은_거절한다() {
        NotEditableException e = assertThrows(NotEditableException.class, () -> TextFileCodec.decode(utf8("a\rb")));

        assertEquals(Problem.LONE_CR, e.problem());
    }

    @Test
    void 편집한_글자도_같은_규칙으로_저장한다() {
        assertArrayEquals(utf8("x\r\ny"), TextFileCodec.encode("x\ny", true, false));
        assertArrayEquals(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'x'}, TextFileCodec.encode("x", false, true));
    }
}
