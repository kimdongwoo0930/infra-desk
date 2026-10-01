package com.infradesk.alert;

/** 알림을 전달한다. 블로킹이므로 EDT에서 호출하지 않는다. */
public interface Notifier {

    /** @throws AlertException 전달에 실패했을 때. 사용자에게 보여줄 메시지가 담겨 있다 */
    void send(Alert alert);
}
