package com.infradesk.service;

import com.infradesk.core.Account;
import com.infradesk.core.Server;
import com.infradesk.core.ServerGrouping;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * 서버를 묶는 규칙. 사이드바와 개요가 같은 규칙을 쓰도록 한 곳에 둔다. 화면은 이 결과를 순서대로 그리기만 한다.
 */
public final class ServerGroups {

    private ServerGroups() {
    }

    /**
     * 묶음 안의 서버 하나.
     *
     * @param inventory 그 서버가 속한 계정의 조회 결과(계정 설정 열기 등에 쓴다)
     */
    public record Member(Server server, AccountInventory inventory) {
    }

    /**
     * 묶음 하나.
     *
     * @param title   묶음 이름. "계정별"에서는 null이고(화면이 계정 이름·리전으로 머리글을 만든다), "묶지 않음"에서도 null이다
     * @param account "계정별"일 때 그 계정의 조회 결과(오류 문구에 쓴다). 아니면 null
     */
    public record Group(String title, AccountInventory account, List<Member> members) {
    }

    /** "계정별" 묶음의 이름. 예: "Oracle · 내 계정". 사이드바와 개요가 같은 글자를 쓰도록 여기 둔다. */
    public static String accountTitle(Account account) {
        return account.provider().displayName().replace(" Cloud", "") + " · " + account.displayName();
    }

    /**
     * @param keep     묶음에 넣을 서버를 고르는 조건(검색어, 상태 거르기). 모두 넣으려면 {@code s -> true}
     * @param keepEmpty 서버가 하나도 없는 묶음도 돌려줄지. 계정별에서 서버가 없거나 불러오지 못한 계정을 보여 주려면 true.
     *                  상태별에는 영향이 없다(빈 묶음은 항상 뺀다)
     */
    public static List<Group> of(List<AccountInventory> inventory, ServerGrouping grouping, Predicate<Server> keep,
                                 boolean keepEmpty) {
        return switch (grouping) {
            case ACCOUNT -> byAccount(inventory, keep, keepEmpty);
            case STATUS -> byStatus(inventory, keep);
            case NONE -> none(inventory, keep);
        };
    }

    private static List<Group> byAccount(List<AccountInventory> inventory, Predicate<Server> keep, boolean keepEmpty) {
        List<Group> groups = new ArrayList<>();
        for (AccountInventory inv : inventory) {
            List<Member> members = inv.servers().stream().filter(keep).map(s -> new Member(s, inv)).toList();
            if (!members.isEmpty() || keepEmpty) {
                groups.add(new Group(null, inv, members));
            }
        }
        return groups;
    }

    private static List<Group> byStatus(List<AccountInventory> inventory, Predicate<Server> keep) {
        List<Member> running = new ArrayList<>();
        List<Member> stopped = new ArrayList<>();
        List<Member> other = new ArrayList<>();
        for (AccountInventory inv : inventory) {
            for (Server s : inv.servers()) {
                if (!keep.test(s)) {
                    continue;
                }
                (switch (s.status()) {
                    case RUNNING -> running;
                    case STOPPED, TERMINATED -> stopped;
                    default -> other;   // 시작·정지·재부팅 중, 응답 없음, 알 수 없음
                }).add(new Member(s, inv));
            }
        }
        List<Group> groups = new ArrayList<>();
        addIfAny(groups, "실행 중", running);
        addIfAny(groups, "정지", stopped);
        addIfAny(groups, "그 밖", other);
        return groups;
    }

    private static List<Group> none(List<AccountInventory> inventory, Predicate<Server> keep) {
        List<Member> all = new ArrayList<>();
        for (AccountInventory inv : inventory) {
            inv.servers().stream().filter(keep).forEach(s -> all.add(new Member(s, inv)));
        }
        return all.isEmpty() ? List.of() : List.of(new Group(null, null, all));
    }

    private static void addIfAny(List<Group> groups, String title, List<Member> members) {
        if (!members.isEmpty()) {
            groups.add(new Group(title, null, members));
        }
    }
}
