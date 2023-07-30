package moe.echo.bramblingnote.user;

import org.mapstruct.*;

@Mapper(componentModel = "spring")
public interface UserMapper {
    @Mapping(target = "password", ignore = true)
    UserDto toUserDto(UserEntity user);

    UserEntity toUser(UserDto userDto);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    UserEntity toUser(UserDto userDto, @MappingTarget UserEntity user);
}
