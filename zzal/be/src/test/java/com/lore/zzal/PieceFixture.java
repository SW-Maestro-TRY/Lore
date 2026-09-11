package com.lore.zzal;

import com.lore.zzal.piece.PieceService;
import com.lore.zzal.piece.ZzalPiece;
import com.lore.zzal.piece.ZzalPieceRepository;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 조각을 실제로 세는 {@link PieceService} — 메모리 저장소로.
 *
 * ★ 서비스 시험에서 조각을 목으로 죽여 두면 <b>세는 줄이 도는지</b>를 아무도 안 본다.
 *   규칙은 {@link ZzalPiece} 안에 있고 이 서비스는 줄을 찾아 건네줄 뿐이라, 진짜를 쓰는 편이 싸다.
 */
public final class PieceFixture {

    private PieceFixture() {
    }

    public static PieceService inMemory() {
        return inMemory(new HashMap<>());
    }

    public static PieceService inMemory(Map<Long, ZzalPiece> store) {
        ZzalPieceRepository repository = mock(ZzalPieceRepository.class);
        when(repository.findById(anyLong()))
                .thenAnswer(i -> Optional.ofNullable(store.get(i.getArgument(0, Long.class))));
        when(repository.save(any(ZzalPiece.class))).thenAnswer(i -> {
            ZzalPiece row = i.getArgument(0);
            store.put(row.getPetId(), row);
            return row;
        });
        return new PieceService(repository);
    }
}
