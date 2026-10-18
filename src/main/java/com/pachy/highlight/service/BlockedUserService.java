package com.pachy.highlight.service;

import com.pachy.highlight.entity.BlockedUser;
import com.pachy.highlight.repository.BlockedUserRepository;
import com.pachy.highlight.repository.ChatRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 비수집(블랙리스트) 회원 관리.
 *
 * <p>등록되면 (1) 이후 채팅 수집에서 해당 uid 를 건너뛰고, (2) 이미 저장된 채팅을 즉시 삭제한다.
 * 수집 경로에서 매 채팅마다 DB 를 조회하지 않도록 uid 집합을 메모리에 캐시하고,
 * 등록/해제 시 캐시를 무효화한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BlockedUserService {

    private final BlockedUserRepository blockedUserRepository;
    private final ChatRepository chatRepository;
    private final com.pachy.highlight.repository.HighlightRepository highlightRepository;

    private final Set<String> cachedUids = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean cacheLoaded = new AtomicBoolean(false);

    public List<BlockedUser> list() {
        return blockedUserRepository.findAllByOrderByCreatedAtDesc();
    }

    /** 등록 전 확인용: 해당 uid 로 저장된 채팅 수와 닉네임. */
    public List<String> nicknamesOf(String uid) {
        return chatRepository.findNicknamesByUserId(uid);
    }

    public long chatCountOf(String uid) {
        return chatRepository.countByUserId(uid);
    }

    public boolean isRegistered(String uid) {
        return blockedUserRepository.existsByUid(uid);
    }

    /**
     * 비수집 회원으로 등록하고 기존 채팅을 삭제한다.
     * 이미 등록된 uid 면 채팅만 다시 삭제하고 기존 항목을 반환한다.
     */
    @Transactional
    public BlockedUser block(String uid, String nickname, String memo) {
        String trimmed = uid.trim();
        highlightRepository.deleteBySourceChatUserId(trimmed);
        int deleted = chatRepository.deleteByUserId(trimmed);

        BlockedUser saved = blockedUserRepository.findByUid(trimmed)
                .map(existing -> {
                    if (nickname != null && !nickname.isBlank()) existing.setNickname(nickname.trim());
                    if (memo != null && !memo.isBlank()) existing.setMemo(memo.trim());
                    existing.setDeletedChats(existing.getDeletedChats() + deleted);
                    return blockedUserRepository.save(existing);
                })
                .orElseGet(() -> blockedUserRepository.save(BlockedUser.builder()
                        .uid(trimmed)
                        .nickname(nickname != null && !nickname.isBlank() ? nickname.trim() : null)
                        .memo(memo != null && !memo.isBlank() ? memo.trim() : null)
                        .deletedChats(deleted)
                        .build()));

        invalidateCache();
        log.info("비수집 회원 등록 - uid: {}, 삭제된 채팅: {}건", trimmed, deleted);
        return saved;
    }

    /** 비수집 등록을 해제한다(이미 삭제된 채팅은 복구되지 않는다). */
    @Transactional
    public boolean unblock(Long id) {
        if (!blockedUserRepository.existsById(id)) return false;
        blockedUserRepository.deleteById(id);
        invalidateCache();
        return true;
    }

    /** 수집 필터. 등록된 uid 면 true. */
    public boolean isBlocked(String uid) {
        if (uid == null) return false;
        return blockedUids().contains(uid);
    }

    /** 등록된 uid 집합(캐시). 조회 실패 시 빈 집합을 돌려 수집을 막지 않는다. */
    public Set<String> blockedUids() {
        if (cacheLoaded.get()) return cachedUids;
        synchronized (cachedUids) {
            if (!cacheLoaded.get()) {
                try {
                    cachedUids.clear();
                    cachedUids.addAll(blockedUserRepository.findAllUids());
                    cacheLoaded.set(true);
                } catch (Exception e) {
                    log.warn("비수집 회원 목록 조회 실패 - 이번 수집은 필터 없이 진행합니다", e);
                    return Set.of();
                }
            }
        }
        return cachedUids;
    }

    private void invalidateCache() {
        cacheLoaded.set(false);
    }
}
