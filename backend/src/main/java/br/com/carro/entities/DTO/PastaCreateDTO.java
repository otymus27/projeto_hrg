package br.com.carro.entities.DTO;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record PastaCreateDTO(
        @NotBlank(message = "O nome da pasta não pode estar em branco.")
        String nome,
        Long pastaPaiId,
        List<Long> usuariosComPermissaoIds
) {}
