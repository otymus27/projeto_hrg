package br.com.carro.services;

import br.com.carro.entities.DTO.formularios.*;
import br.com.carro.entities.Formulario;
import br.com.carro.entities.PastaFormulario;
import br.com.carro.entities.Usuario.Usuario;
import br.com.carro.repositories.FormularioRepository;
import br.com.carro.repositories.PastaFormularioRepository;
import br.com.carro.utils.ArquivoUtils;
import br.com.carro.utils.AuthService;
import br.com.carro.utils.FileUtils;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

@Service
public class FormularioService {

    private final PastaFormularioRepository pastaFormularioRepository;
    private final FormularioRepository formularioRepository;
    private ArquivoUtils fileUtils;
    private final AuthService authService;

    @Value("${storage.root-dir}/formularios")
    private String rootDirectory;

    private final Path baseDir;

    public FormularioService(PastaFormularioRepository pastaFormularioRepository,
                             FormularioRepository formularioRepository,
                             @Value("${formularios.base-dir}") String baseDirConfig,
                             AuthService authService, ArquivoUtils fileUtils) {
        this.pastaFormularioRepository = pastaFormularioRepository;
        this.formularioRepository = formularioRepository;
        this.authService = authService;
        this.fileUtils = fileUtils;
        this.baseDir = Paths.get(baseDirConfig);
    }

    // ==========================
    // 📂 Pastas
    // ==========================

    @Transactional(readOnly = true)
    public List<PastaFormularioDTO> listarPastas()  throws AccessDeniedException{
        List<PastaFormulario> pastas = pastaFormularioRepository.findAll();

        if (pastas == null || pastas.isEmpty()) {
            return Collections.emptyList();
        }

        return pastas.stream()
                .map(pasta -> {
                    long quantidade = formularioRepository.countByPastaFormularioId(pasta.getId());
                    long tamanho = formularioRepository.findByPastaFormulario(pasta)
                            .stream()
                            .mapToLong(Formulario::getTamanho)
                            .sum();

                    return PastaFormularioDTO.fromEntity(pasta, quantidade, tamanho);
                })
                .toList();
    }

    // Lista as pastas e seus formularios para funcionar como se fosse um explorer de arquivos
    @Transactional(readOnly = true)
    public List<PastaExplorerDTO> listarPastasHierarquicas(ExplorerFilterRequest filter) {
        return pastaFormularioRepository.findAll().stream()
                .map(pasta -> {
                    long quantidade = formularioRepository.countByPastaFormularioId(pasta.getId());
                    long tamanho = formularioRepository.findByPastaFormulario(pasta)
                            .stream()
                            .mapToLong(Formulario::getTamanho)
                            .sum();

                    // 🔍 filtros + ordenação nos arquivos
                    List<FormularioDTO> formularios = formularioRepository.findByPastaFormulario(pasta)
                            .stream()
                            .map(FormularioDTO::fromEntity)
                            .filter(f -> filter.nomeArquivo() == null || f.nomeArquivo().toLowerCase().contains(filter.nomeArquivo().toLowerCase()))
                            .filter(f -> filter.extensao() == null || f.extensao().equalsIgnoreCase(filter.extensao()))
                            .filter(f -> filter.tamanhoMin() == null || f.tamanho() >= filter.tamanhoMin())
                            .filter(f -> filter.tamanhoMax() == null || f.tamanho() <= filter.tamanhoMax())
                            .filter(f -> filter.dataUploadInicial() == null || !f.dataUpload().isBefore(filter.dataUploadInicial()))
                            .filter(f -> filter.dataUploadFinal() == null || !f.dataUpload().isAfter(filter.dataUploadFinal()))
                            .sorted(getArquivoComparator(filter.sortBy(), filter.order()))
                            .toList();

                    return PastaExplorerDTO.fromEntity(pasta, quantidade, tamanho, formularios);
                })
                // 🔍 filtros de pasta
                .filter(p -> filter.nomePasta() == null || p.nomePasta().toLowerCase().contains(filter.nomePasta().toLowerCase()))
                .filter(p -> filter.tamanhoMin() == null || p.tamanho() >= filter.tamanhoMin())
                .filter(p -> filter.tamanhoMax() == null || p.tamanho() <= filter.tamanhoMax())
                // ↕️ ordenação de pastas
                .sorted(getPastaComparator(filter.sortBy(), filter.order()))
                .toList();
    }






    @Transactional
    public PastaFormulario criarPasta(CriarPastaFormularioRequest req, Authentication authentication)
            throws AccessDeniedException {

        if (!StringUtils.hasText(req.nomePasta())) {
            throw new IllegalArgumentException("Nome da pasta é obrigatório");
        }

        Path pastaDestino = baseDir.resolve(FileUtils.sanitizeFileName(req.nomePasta()));
        try {
            Files.createDirectories(pastaDestino);
        } catch (IOException e) {
            throw new RuntimeException("Erro ao criar diretório físico da pasta", e);
        }

        PastaFormulario pasta = new PastaFormulario();
        pasta.setNomePasta(req.nomePasta());
        pasta.setDescricao(req.descricao());
        pasta.setCaminhoCompleto(pastaDestino.toString());
        pasta.setDataCriacao(LocalDateTime.now());
        pasta.setDataAtualizacao(LocalDateTime.now());

        // ✅ vincula o usuário logado
        Usuario usuario = authService.getUsuarioLogado(authentication);
        if (usuario != null) {
            pasta.setCriadoPor(usuario);
        }

        return pastaFormularioRepository.save(pasta);
    }




    // RF-018: Renomear pasta de formulários
    @Transactional
    public PastaFormulario renomearPasta(Long pastaId, String novoNome) throws AccessDeniedException {
        PastaFormulario pasta = pastaFormularioRepository.findById(pastaId)
                .orElseThrow(() -> new EntityNotFoundException("Pasta não encontrada."));

        // Diretório atual
        Path pastaAtual = Paths.get(pasta.getCaminhoCompleto());

        // Novo caminho dentro do rootDirectory
        Path novoCaminho = Paths.get(rootDirectory, FileUtils.sanitizeFileName(novoNome));

        if (Files.exists(novoCaminho)) {
            throw new IllegalArgumentException("Já existe uma pasta raiz com este nome.");
        }

        try {
            Files.move(pastaAtual, novoCaminho);
        } catch (IOException e) {
            throw new RuntimeException("Erro ao renomear a pasta no sistema de arquivos.", e);
        }

        pasta.setNomePasta(novoNome);
        pasta.setCaminhoCompleto(novoCaminho.toString());
        pasta.setDataAtualizacao(LocalDateTime.now());

        return pastaFormularioRepository.save(pasta);
    }




    @Transactional
    public void excluirPasta(Long pastaId) throws AccessDeniedException{
        PastaFormulario pasta = pastaFormularioRepository.findById(pastaId)
                .orElseThrow(() -> new RuntimeException("Pasta não encontrada"));

        if (formularioRepository.countByPastaFormularioId(pasta.getId()) > 0) {
            throw new IllegalStateException("A pasta possui formulários. Exclua-os antes de remover a pasta.");
        }

        pastaFormularioRepository.delete(pasta);
    }

    // ==========================
    // 📄 Formulários
    // ==========================

    @Transactional(readOnly = true)
    public List<FormularioDTO> listarFormularios(Long pastaId) throws AccessDeniedException{
        PastaFormulario pasta = pastaFormularioRepository.findById(pastaId)
                .orElseThrow(() -> new EntityNotFoundException("Pasta não encontrada com ID: " + pastaId));

        List<Formulario> formularios = formularioRepository.findByPastaFormulario(pasta);

        if (formularios == null || formularios.isEmpty()) {
            return Collections.emptyList();
        }

        return formularios.stream()
                .map(FormularioDTO::fromEntity)
                .toList();
    }


    @Transactional
    public UploadFormularioResponse uploadFormulario(Long pastaId,
                                                     MultipartFile file,
                                                     Authentication authentication)
            throws AccessDeniedException {

        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("O arquivo enviado não pode ser vazio.");
        }

        PastaFormulario pasta = pastaFormularioRepository.findById(pastaId)
                .orElseThrow(() -> new EntityNotFoundException("Pasta não encontrada com ID: " + pastaId));

        // ✅ Permissões: apenas ADMIN ou GERENTE podem subir arquivos
        Usuario usuario = authService.getUsuarioLogado(authentication);

        // 🚨 Garante que o diretório existe
        Path destino = Paths.get(pasta.getCaminhoCompleto());
        if (!Files.exists(destino)) {
            throw new IllegalStateException("O diretório da pasta não existe no filesystem: " + destino);
        }

        // 📂 Salvar arquivo físico
        Path caminhoArquivo = destino.resolve(FileUtils.sanitizeFileName(file.getOriginalFilename()));

        try {
            Files.copy(file.getInputStream(), caminhoArquivo);
        } catch (FileAlreadyExistsException e) {
            throw new IllegalArgumentException("Já existe um arquivo com este nome na pasta.");
        } catch (IOException e) {
            throw new RuntimeException("Erro ao salvar o arquivo no sistema de arquivos.", e);
        }

        // 💾 Salvar no banco
        Formulario formulario = new Formulario();
        formulario.setNomeArquivo(file.getOriginalFilename());
        formulario.setCaminhoArmazenamento(destino.toString());
        formulario.setTamanho(file.getSize());
        formulario.setPastaFormulario(pasta);
        formulario.setDataUpload(LocalDateTime.now());
        formulario.setCriadoPor(usuario);

        formularioRepository.save(formulario);

        return new UploadFormularioResponse(
                formulario.getId(),
                formulario.getNomeArquivo(),
                formulario.getTamanho(),
                pasta.getId(),
                pasta.getNomePasta(),
                "Upload realizado com sucesso."
        );
    }

    @Transactional
    public List<UploadFormularioResponse> uploadMultiplos(Long pastaId,
                                                          List<MultipartFile> files,
                                                          Authentication authentication)
            throws IOException {

        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("É necessário enviar pelo menos um arquivo.");
        }

        PastaFormulario pasta = pastaFormularioRepository.findById(pastaId)
                .orElseThrow(() -> new EntityNotFoundException("Pasta não encontrada com ID: " + pastaId));

        // 🚨 Garante que o diretório existe
        Path destino = Paths.get(pasta.getCaminhoCompleto());
        if (!Files.exists(destino)) {
            throw new IllegalStateException("O diretório da pasta não existe no filesystem: " + destino);
        }

        Usuario usuario = authService.getUsuarioLogado(authentication);
        List<UploadFormularioResponse> responses = new ArrayList<>();

        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                throw new IllegalArgumentException("Um dos arquivos enviados está vazio.");
            }

            Path caminhoArquivo = destino.resolve(FileUtils.sanitizeFileName(file.getOriginalFilename()));

            try {
                Files.copy(file.getInputStream(), caminhoArquivo);
            } catch (FileAlreadyExistsException e) {
                throw new IllegalArgumentException("Já existe um arquivo chamado '" + file.getOriginalFilename() + "' na pasta.");
            } catch (IOException e) {
                throw new RuntimeException("Erro ao salvar o arquivo '" + file.getOriginalFilename() + "' no sistema de arquivos.", e);
            }

            // 💾 Salvar no banco
            Formulario formulario = new Formulario();
            formulario.setNomeArquivo(file.getOriginalFilename());
            formulario.setCaminhoArmazenamento(destino.toString());
            formulario.setTamanho(file.getSize());
            formulario.setPastaFormulario(pasta);
            formulario.setDataUpload(LocalDateTime.now());
            formulario.setCriadoPor(usuario);

            formularioRepository.save(formulario);

            responses.add(new UploadFormularioResponse(
                    formulario.getId(),
                    formulario.getNomeArquivo(),
                    formulario.getTamanho(),
                    pasta.getId(),
                    pasta.getNomePasta(),
                    "Upload realizado com sucesso."
            ));
        }

        return responses;
    }


    @Transactional
    public UploadFormularioResponse substituirFormulario(Long formularioId,
                                                         MultipartFile novoArquivo) throws IOException {
        if (novoArquivo == null || novoArquivo.isEmpty()) {
            throw new IllegalArgumentException("O novo arquivo não pode ser vazio.");
        }

        Formulario formulario = formularioRepository.findById(formularioId)
                .orElseThrow(() -> new EntityNotFoundException("Formulário não encontrado com ID: " + formularioId));

        PastaFormulario pasta = formulario.getPastaFormulario();
        if (pasta == null) {
            throw new IllegalStateException("O formulário não está vinculado a nenhuma pasta.");
        }

        // 🚨 Garante que a pasta existe no filesystem
        Path pastaDestino = Paths.get(pasta.getCaminhoCompleto());
        if (!Files.exists(pastaDestino)) {
            throw new IllegalStateException("O diretório da pasta não existe no filesystem: " + pastaDestino);
        }

        // 📂 Substituir arquivo físico
        Path caminhoArquivo = pastaDestino.resolve(FileUtils.sanitizeFileName(novoArquivo.getOriginalFilename()));

        try {
            // Sobrescreve o arquivo existente
            Files.copy(novoArquivo.getInputStream(), caminhoArquivo, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException("Erro ao substituir o arquivo no sistema de arquivos.", e);
        }

        // 💾 Atualizar informações do formulário no banco
        formulario.setNomeArquivo(novoArquivo.getOriginalFilename());
        formulario.setTamanho(novoArquivo.getSize());
        formulario.setDataUpload(LocalDateTime.now());

        Formulario salvo = formularioRepository.save(formulario);

        return new UploadFormularioResponse(
                salvo.getId(),
                salvo.getNomeArquivo(),
                salvo.getTamanho(),
                pasta.getId(),
                pasta.getNomePasta(),
                "Formulário substituído com sucesso."
        );
    }



    @Transactional
    public void excluirFormulario(Long formularioId) throws AccessDeniedException {
        Formulario formulario = formularioRepository.findById(formularioId)
                .orElseThrow(() -> new EntityNotFoundException("Formulário não encontrado com ID: " + formularioId));

        PastaFormulario pasta = formulario.getPastaFormulario();
        if (pasta == null) {
            throw new IllegalStateException("O formulário não está vinculado a nenhuma pasta.");
        }

        Path caminhoArquivo = Paths.get(pasta.getCaminhoCompleto(), formulario.getNomeArquivo());

        // 🚨 Verifica se o arquivo físico existe
        if (!Files.exists(caminhoArquivo)) {
            throw new IllegalStateException("O arquivo físico não foi encontrado no caminho: " + caminhoArquivo);
        }

        // 🗑️ Tenta excluir arquivo físico
        try {
            Files.delete(caminhoArquivo);
        } catch (IOException e) {
            throw new RuntimeException("Erro ao excluir o arquivo físico: " + caminhoArquivo, e);
        }

        // 💾 Excluir registro do banco
        formularioRepository.delete(formulario);
    }


    @Transactional
    public void excluirVarios(List<Long> ids) throws AccessDeniedException{
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("É necessário informar ao menos um ID de formulário para exclusão.");
        }

        for (Long id : ids) {
            Formulario formulario = formularioRepository.findById(id)
                    .orElseThrow(() -> new EntityNotFoundException("Formulário não encontrado com ID: " + id));

            PastaFormulario pasta = formulario.getPastaFormulario();
            if (pasta == null) {
                throw new IllegalStateException("O formulário com ID " + id + " não está vinculado a nenhuma pasta.");
            }

            Path caminhoArquivo = Paths.get(pasta.getCaminhoCompleto(), formulario.getNomeArquivo());

            // 🚨 Verifica se o arquivo físico existe
            if (!Files.exists(caminhoArquivo)) {
                throw new IllegalStateException("O arquivo físico do formulário ID " + id + " não foi encontrado em: " + caminhoArquivo);
            }

            // 🗑️ Exclui arquivo físico
            try {
                Files.delete(caminhoArquivo);
            } catch (IOException e) {
                throw new RuntimeException("Erro ao excluir o arquivo físico do formulário ID " + id, e);
            }

            // 💾 Exclui registro no banco
            formularioRepository.delete(formulario);
        }
    }



    @Transactional(readOnly = true)
    public Resource downloadFormulario(Long formularioId) throws IOException {
        Formulario formulario = formularioRepository.findById(formularioId)
                .orElseThrow(() -> new EntityNotFoundException("Formulário não encontrado com ID: " + formularioId));

        PastaFormulario pasta = formulario.getPastaFormulario();
        if (pasta == null) {
            throw new IllegalStateException("O formulário não está vinculado a nenhuma pasta.");
        }

        Path caminhoArquivo = Paths.get(pasta.getCaminhoCompleto(), formulario.getNomeArquivo());

        // 🚨 Verifica se o arquivo físico existe
        if (!Files.exists(caminhoArquivo)) {
            throw new IllegalStateException("O arquivo físico não foi encontrado no caminho: " + caminhoArquivo);
        }

        try {
            return new UrlResource(caminhoArquivo.toUri());
        } catch (MalformedURLException e) {
            throw new RuntimeException("Erro ao carregar o arquivo para download: " + caminhoArquivo, e);
        }
    }


    // ==========================
    // 🔧 Helpers
    // ==========================
    private FormularioDTO toDTO(Formulario f) {
        String ext = "";
        int i = f.getNomeArquivo().lastIndexOf('.');
        if (i >= 0 && i < f.getNomeArquivo().length() - 1) {
            ext = f.getNomeArquivo().substring(i + 1);
        }

        return new FormularioDTO(
                f.getId(),
                f.getNomeArquivo(),
                ext,
                f.getTamanho(),
                f.getDataUpload(),
                f.getDataAtualizacao()
        );
    }

    private Comparator<PastaExplorerDTO> getPastaComparator(String sortBy, String order) {
        Comparator<PastaExplorerDTO> comp;

        switch (sortBy) {
            case "tamanho" -> comp = Comparator.comparingLong(PastaExplorerDTO::tamanho);
            case "dataCriacao" -> comp = Comparator.comparing(PastaExplorerDTO::dataCriacao,
                    Comparator.nullsLast(Comparator.naturalOrder()));
            case "dataAtualizacao" -> comp = Comparator.comparing(PastaExplorerDTO::dataAtualizacao,
                    Comparator.nullsLast(Comparator.naturalOrder()));
            default -> comp = Comparator.comparing(PastaExplorerDTO::nomePasta, String.CASE_INSENSITIVE_ORDER);
        }

        return "desc".equalsIgnoreCase(order) ? comp.reversed() : comp;
    }

    private Comparator<FormularioDTO> getArquivoComparator(String sortBy, String order) {
        Comparator<FormularioDTO> comp;

        switch (sortBy) {
            case "tamanho" -> comp = Comparator.comparingLong(FormularioDTO::tamanho);
            case "dataUpload" -> comp = Comparator.comparing(FormularioDTO::dataUpload,
                    Comparator.nullsLast(Comparator.naturalOrder()));
            case "dataAtualizacao" -> comp = Comparator.comparing(FormularioDTO::dataAtualizacao,
                    Comparator.nullsLast(Comparator.naturalOrder()));
            default -> comp = Comparator.comparing(FormularioDTO::nomeArquivo, String.CASE_INSENSITIVE_ORDER);
        }

        return "desc".equalsIgnoreCase(order) ? comp.reversed() : comp;
    }


}
