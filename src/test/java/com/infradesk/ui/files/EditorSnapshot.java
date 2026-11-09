package com.infradesk.ui.files;

import javax.swing.JDialog;

/**
 * 스냅샷 도구(UiSnapshot)가 앱 안 편집 창을 화면에 띄우지 않고 그릴 수 있게 만들어 준다. 편집 창은 패키지 전용 클래스라
 * 다른 패키지의 도구가 직접 만들 수 없어서 같은 패키지에 둔다.
 */
public final class EditorSnapshot {

    private EditorSnapshot() {
    }

    /** 데모 설정 파일을 연 편집 창. 한 줄을 고쳐서 "수정됨" 표시가 보이게 한다. 화면에 띄우지는 않는다. */
    public static JDialog create(String serverName, String path) {
        String text = """
                server {
                    listen 80;
                    server_name demo.example.com;

                    location / {
                        proxy_pass http://127.0.0.1:8080;
                        proxy_set_header Host $host;
                        proxy_set_header X-Real-IP $remote_addr;
                    }

                    location /health {
                        return 200 "ok";
                    }
                }
                """;
        FileEditorWindow window = new FileEditorWindow(null, serverName, path,
                new TextFileCodec.Decoded(text, false, false, false), (w, bytes, done) -> { });
        window.textArea().setText(text.replace("listen 80;", "listen 8080;"));
        return window;
    }
}
