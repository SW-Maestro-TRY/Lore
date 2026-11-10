package com.lore.webtoon.community;

import com.lore.webtoon.character.*;
import com.lore.webtoon.credit.BrowserLinkRepository;
import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.PageRequest;
import java.time.Instant;
import java.util.*;

@Service
public class PublicCharacters {
    private final WebtoonCharacterRepository rows;
    private final CharacterService characters;
    private final BrowserLinkRepository links;
    public PublicCharacters(WebtoonCharacterRepository rows,CharacterService characters,BrowserLinkRepository links) { this.rows=rows;this.characters=characters;this.links=links; }
    public List<Map<String,Object>> browse(int page) {
        return rows.community(PageRequest.of(Math.max(0,Math.min(page,1000)),24)).stream().map(this::view).toList();
    }
    public Map<String,Object> one(String id) {
        return view(rows.findByPublicId(id).filter(WebtoonCharacter::isCommunityPublic).orElseThrow(CommunityService::missing));
    }
    @Transactional
    public Map<String,Object> publish(String id,Long me,boolean value,boolean agreed) {
        CommunityService.login(me);
        var c=rows.lockForPublication(id).orElseThrow(CommunityService::missing);
        boolean owner=c.ownedBy(me) || (c.getOwnerId()==null && c.getBrowserUid()!=null && links.existsByUserIdAndBrowserUid(me,c.getBrowserUid()));
        if(!owner || c.isBuiltin()) throw CommunityService.missing();
        if(value && (c.isCommunityBlocked() || c.getStatus()!=CharacterStatus.READY || !agreed))
            throw new BusinessException(ErrorCode.INVALID_INPUT,"완성된 캐릭터만 이용 범위에 동의한 뒤 공개할 수 있어요.");
        c.publishForReuse(value,me,Instant.now()); rows.save(c);
        return Map.of("published",c.isCommunityPublic());
    }
    private Map<String,Object> view(WebtoonCharacter c) {
        Map<String,Object> m=new LinkedHashMap<>();
        m.put("id",c.getPublicId());m.put("name",c.getName());m.put("description",c.getDescription());
        m.put("art_url",characters.artUrl(c));m.put("creator","창작자 "+c.getOwnerId());
        return m;
    }
}
