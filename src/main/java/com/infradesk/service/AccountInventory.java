package com.infradesk.service;

import com.infradesk.core.Account;
import com.infradesk.core.Server;

import java.util.List;

/**
 * 계정 하나의 서버를 불러온 결과.
 *
 * @param error 불러오기에 실패했을 때 사용자에게 보여줄 메시지. 아니면 null
 */
public record AccountInventory(Account account, List<Server> servers, String error) {

    public AccountInventory {
        servers = List.copyOf(servers);
    }

    public boolean failed() {
        return error != null;
    }
}
