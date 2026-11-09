package com.infradesk.ui.files;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * 파일 편집 창이 파일을 글자로 바꾸고 다시 바이트로 되돌리는 규칙. 화면 없이 시험할 수 있게 Swing에 기대지 않는다.
 *
 * <p>파일을 망가뜨리지 않는 것이 목적이다. 그래서 확실히 글자로 읽히는 파일(UTF-8)만 연다. 다른 인코딩(EUC-KR 등)이나
 * 바이너리를 억지로 열어 저장하면 글자가 깨지므로 {@link NotEditableException}으로 거절하고, 부르는 쪽이 받기나
 * 외부 편집기를 권한다. 줄바꿈(LF/CRLF)과 UTF-8 BOM은 읽을 때 기억해 두었다가 저장할 때 그대로 되돌린다.
 */
final class TextFileCodec {

    /** 편집 창에서 열 수 없는 이유. */
    enum Problem {
        /** 0 바이트가 있다: 이미지·압축 파일 같은 바이너리. */
        BINARY,
        /** UTF-8로 읽히지 않는다: EUC-KR 같은 다른 인코딩이거나 깨진 파일. */
        NOT_UTF8,
        /** 줄바꿈이 CR 하나뿐인 곳이 있다(아주 오래된 형식). */
        LONE_CR
    }

    static final class NotEditableException extends Exception {
        private final Problem problem;

        NotEditableException(Problem problem) {
            super(problem.name());
            this.problem = problem;
        }

        Problem problem() {
            return problem;
        }
    }

    /**
     * 읽은 결과.
     *
     * @param text          편집 창에 넣을 글자(줄바꿈은 모두 {@code \n})
     * @param crlf          파일의 줄바꿈이 CRLF였나(저장할 때 되돌린다)
     * @param bom           UTF-8 BOM이 있었나(저장할 때 다시 붙인다)
     * @param mixedEndings  LF와 CRLF가 섞여 있었나(저장하면 하나로 통일된다)
     */
    record Decoded(String text, boolean crlf, boolean bom, boolean mixedEndings) {
    }

    private TextFileCodec() {
    }

    static Decoded decode(byte[] data) throws NotEditableException {
        int start = 0;
        boolean bom = false;
        if (data.length >= 3 && (data[0] & 0xFF) == 0xEF && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF) {
            start = 3;
            bom = true;
        }
        for (int i = start; i < data.length; i++) {
            if (data[i] == 0) {
                throw new NotEditableException(Problem.BINARY);
            }
        }
        String raw;
        try {
            raw = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data, start, data.length - start)).toString();
        } catch (CharacterCodingException e) {
            throw new NotEditableException(Problem.NOT_UTF8);
        }
        int crlf = 0;
        int lf = 0;
        StringBuilder text = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\r') {
                if (i + 1 < raw.length() && raw.charAt(i + 1) == '\n') {
                    crlf++;
                    text.append('\n');
                    i++;
                } else {
                    throw new NotEditableException(Problem.LONE_CR);
                }
            } else {
                if (c == '\n') {
                    lf++;
                }
                text.append(c);
            }
        }
        return new Decoded(text.toString(), crlf > lf, bom, crlf > 0 && lf > 0);
    }

    /** 편집 창의 글자를 파일에 쓸 바이트로 되돌린다. */
    static byte[] encode(String text, boolean crlf, boolean bom) {
        byte[] body = (crlf ? text.replace("\n", "\r\n") : text).getBytes(StandardCharsets.UTF_8);
        if (!bom) {
            return body;
        }
        byte[] out = new byte[body.length + 3];
        out[0] = (byte) 0xEF;
        out[1] = (byte) 0xBB;
        out[2] = (byte) 0xBF;
        System.arraycopy(body, 0, out, 3, body.length);
        return out;
    }
}
