package org.booklore.mapper.custom;

import org.booklore.mapper.LibraryMapper;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.entity.BookLoreUserEntity;
import org.booklore.model.entity.UserPermissionsEntity;
import org.booklore.model.entity.UserSettingEntity;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class BookLoreUserTransformerBrowsePaginationTest {

    @Test
    void readsTheBrowsePaginationSetting() {
        UserSettingEntity setting = new UserSettingEntity();
        setting.setSettingKey("browsePagination");
        setting.setSettingValue("{\"mode\":\"pages\",\"pageSize\":96}");
        BookLoreUserEntity entity = BookLoreUserEntity.builder()
                .id(1L).username("reader")
                .permissions(new UserPermissionsEntity())
                .settings(new HashSet<>(Set.of(setting)))
                .build();

        BookLoreUser user = new BookLoreUserTransformer(new ObjectMapper(), mock(LibraryMapper.class)).toDTO(entity);

        assertThat(user.getUserSettings().getBrowsePagination().getMode()).isEqualTo("pages");
        assertThat(user.getUserSettings().getBrowsePagination().getPageSize()).isEqualTo(96);
    }
}
