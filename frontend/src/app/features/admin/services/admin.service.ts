import { Injectable } from '@angular/core';
import {
  HttpClient,
  HttpErrorResponse,
  HttpParams,
} from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { Usuario } from '../../../models/usuario';
import { environment } from '../../../../environments/environment.prod';

// ---------------- DTOs / Interfaces ----------------
// ✅ INTERFACE PAGINACAO COMPLETA
export interface Paginacao<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  size: number;
  number: number;
  numberOfElements: number;
  first: boolean;
  last: boolean;
  empty: boolean;
  pageable: {
    sort: {
      sorted: boolean;
      unsorted: boolean;
      empty: boolean;
    };
    offset: number;
    pageNumber: number;
    pageSize: number;
    unpaged: boolean;
    paged: boolean;
  };
  sort: {
    sorted: boolean;
    unsorted: boolean;
    empty: boolean;
  };
}
export interface ArquivoAdmin {
  id: number;
  nome: string;
  tipo: string;
  tamanho: number;
  dataAtualizacao: Date;
  criadoPor: string;
  url: string;
}

export interface PastaCompletaDTO {
  id: number;
  nomePasta: string;
  caminhoCompleto: string;
  dataCriacao: Date;
  dataAtualizacao: Date;
  criadoPor: string;
  arquivos: ArquivoAdmin[];
  subPastas: PastaCompletaDTO[];
}

export interface PastaExcluirDTO {
  idsPastas: number[];
  excluirConteudo: boolean;
}

export interface PastaPermissaoAcaoDTO {
  pastaId: number;
  adicionarUsuariosIds: number[];
  removerUsuariosIds: number[];
}

export interface UsuarioResumoDTO {
  id: number;
  username: string;
  nome: string;
}

export interface ErrorMessage {
  status: number;
  error: string;
  message: string;
  path: string;
  timestamp?: string;
}

// ---------------- Serviço ----------------
@Injectable({ providedIn: 'root' })
export class AdminService {
  // private readonly apiUrlAdminPastas = 'http://localhost:8082/api/pastas';
  // private readonly apiUrlAdminArquivos = 'http://localhost:8082/api/arquivos';
  // private readonly apiUrlPublica = 'http://localhost:8082/api/publico';
  // private readonly apiUrlUsuarios = 'http://localhost:8082/api/usuario';

  private readonly apiUrlAdminPastas = `${environment.apiUrl}/pastas`;
  private readonly apiUrlAdminArquivos = `${environment.apiUrl}/arquivos`;
  private readonly apiUrlPublica = `${environment.apiUrl}/publico`;
  private readonly apiUrlUsuarios = `${environment.apiUrl}/usuario`;

  constructor(private http: HttpClient) {}

  // ---------------- Pastas ----------------
  listarConteudoRaiz(
    ordenarPor: string = 'nome',
    ordemAsc: boolean = true,
    nomeBusca?: string
  ): Observable<PastaCompletaDTO[]> {
    let params = new HttpParams()
      .set('ordenarPor', ordenarPor)
      .set('ordemAsc', ordemAsc.toString());

    if (nomeBusca) {
      params = params.set('nomeBusca', nomeBusca);
    }

    return this.http
      .get<PastaCompletaDTO[]>(`${this.apiUrlAdminPastas}/subpastas`, {
        params,
      })
      .pipe(catchError(this.tratarErro));
  }

  getPastaPorId(
    idPasta: number,
    ordenarPor: string = 'nome',
    ordemAsc: boolean = true,
    nomeBusca?: string
  ): Observable<PastaCompletaDTO> {
    let params = new HttpParams()
      .set('ordenarPor', ordenarPor)
      .set('ordemAsc', ordemAsc.toString());

    if (nomeBusca) {
      params = params.set('nomeBusca', nomeBusca);
    }

    return this.http
      .get<PastaCompletaDTO>(`${this.apiUrlAdminPastas}/${idPasta}`, { params })
      .pipe(catchError(this.tratarErro));
  }

  criarPasta(body: {
    nome: string;
    pastaPaiId?: number;
    usuariosComPermissaoIds: number[];
  }): Observable<PastaCompletaDTO> {
    return this.http
      .post<PastaCompletaDTO>(this.apiUrlAdminPastas, body)
      .pipe(catchError(this.tratarErro));
  }

  excluirPasta(id: number): Observable<void> {
    return this.http
      .delete<void>(`${this.apiUrlAdminPastas}/${id}`)
      .pipe(catchError(this.tratarErro));
  }

  renomearPasta(id: number, novoNome: string): Observable<PastaCompletaDTO> {
    return this.http
      .patch<PastaCompletaDTO>(`${this.apiUrlAdminPastas}/${id}/renomear`, {
        novoNome,
      })
      .pipe(catchError(this.tratarErro));
  }

  excluirPastasEmLote(dto: PastaExcluirDTO): Observable<void> {
    return this.http
      .delete<void>(`${this.apiUrlAdminPastas}/excluir-lote`, { body: dto })
      .pipe(catchError(this.tratarErro));
  }

  copiarPasta(
    id: number,
    destinoPastaId?: number
  ): Observable<PastaCompletaDTO> {
    let params = new HttpParams();
    if (destinoPastaId !== undefined) {
      params = params.set('destinoPastaId', String(destinoPastaId));
    }

    return this.http
      .post<PastaCompletaDTO>(
        `${this.apiUrlAdminPastas}/${id}/copiar`,
        {},
        { params }
      ) // ✅ body {}
      .pipe(catchError(this.tratarErro));
  }

  moverPasta(
    idPasta: number,
    novaPastaPaiId?: number
  ): Observable<PastaCompletaDTO> {
    let params = new HttpParams();
    if (novaPastaPaiId !== undefined) {
      params = params.set('novaPastaPaiId', String(novaPastaPaiId));
    }

    return this.http
      .patch<PastaCompletaDTO>(
        `${this.apiUrlAdminPastas}/${idPasta}/mover`,
        {},
        { params }
      ) // ✅ body {}
      .pipe(catchError(this.tratarErro));
  }

  // ---------------- Arquivos ----------------
  abrirArquivo(arquivoId: number): Observable<Blob> {
    return this.http
      .get(`${this.apiUrlAdminArquivos}/visualizar/${arquivoId}`, {
        responseType: 'blob',
      })
      .pipe(catchError(this.tratarErro));
  }

  downloadArquivo(arquivoId: number): Observable<Blob> {
    return this.http
      .get(`${this.apiUrlPublica}/download/arquivo/${arquivoId}`, {
        responseType: 'blob',
      })
      .pipe(catchError(this.tratarErro));
  }

  uploadArquivo(file: File, pastaId: number): Observable<ArquivoAdmin> {
    const formData = new FormData();
    formData.append('file', file);
    formData.append('pastaId', pastaId.toString());
    return this.http
      .post<ArquivoAdmin>(`${this.apiUrlAdminArquivos}/upload`, formData)
      .pipe(catchError(this.tratarErro));
  }

  uploadMultiplosArquivos(
    files: File[],
    pastaId: number
  ): Observable<ArquivoAdmin[]> {
    const formData = new FormData();
    files.forEach((file) => formData.append('arquivos', file));

    return this.http
      .post<ArquivoAdmin[]>(
        `${this.apiUrlAdminArquivos}/pasta/${pastaId}/upload-multiplos`,
        formData
      )
      .pipe(catchError(this.tratarErro));
  }

  excluirArquivo(id: number): Observable<void> {
    return this.http
      .delete<void>(`${this.apiUrlAdminArquivos}/${id}`)
      .pipe(catchError(this.tratarErro));
  }

  renomearArquivo(id: number, novoNome: string): Observable<ArquivoAdmin> {
    return this.http
      .patch<ArquivoAdmin>(`${this.apiUrlAdminArquivos}/renomear/${id}`, {
        novoNome,
      })
      .pipe(catchError(this.tratarErro));
  }

  copiarArquivo(
    arquivoId: number,
    pastaDestinoId: number
  ): Observable<ArquivoAdmin> {
    return this.http
      .post<ArquivoAdmin>(
        `${this.apiUrlAdminArquivos}/${arquivoId}/copiar/${pastaDestinoId}`,
        {}
      ) // ✅ body {}
      .pipe(catchError(this.tratarErro));
  }

  moverArquivo(
    arquivoId: number,
    pastaDestinoId: number
  ): Observable<ArquivoAdmin> {
    return this.http
      .put<ArquivoAdmin>(
        `${this.apiUrlAdminArquivos}/${arquivoId}/mover/${pastaDestinoId}`,
        {}
      ) // ✅ body {}
      .pipe(catchError(this.tratarErro));
  }

  // ---------------- Permissões ----------------
  atualizarPermissoesAcao(dto: PastaPermissaoAcaoDTO): Observable<any> {
    return this.http
      .post<any>(`${this.apiUrlAdminPastas}/permissao/acao`, dto)
      .pipe(catchError(this.tratarErro));
  }

  listarUsuarios(
    termoBusca?: string | null,
    page: number = 0,
    size: number = 10
  ): Observable<Paginacao<Usuario>> {
    let params = new HttpParams()
      .set('page', page.toString())
      .set('size', size.toString());

    if (termoBusca) {
      // 🔎 Decide se busca por username ou nome
      if (/^\d+$/.test(termoBusca) || termoBusca.includes('@')) {
        // se for número (login numérico) ou email → username
        params = params.set('username', termoBusca);
      } else {
        // caso contrário → nome
        params = params.set('nome', termoBusca);
      }
    }

    return this.http
      .get<Paginacao<Usuario>>(this.apiUrlUsuarios, { params })
      .pipe(catchError(this.tratarErro));
  }

  listarUsuariosPorPasta(pastaId: number): Observable<UsuarioResumoDTO[]> {
    return this.http
      .get<UsuarioResumoDTO[]>(`${this.apiUrlAdminPastas}/${pastaId}/usuarios`)
      .pipe(catchError(this.tratarErro));
  }

  // ---------------- Erro centralizado ----------------
  private tratarErro(error: HttpErrorResponse) {
    console.error('Erro capturado no AdminService:', error);
  
    // ✅ Caso especial para uploads muito grandes
    if (error.status === 413) {
      const erroBackend: ErrorMessage = {
        status: 413,
        error: 'Arquivo muito grande',
        message: 'O arquivo enviado excede o limite permitido pelo servidor.',
        path: error.url || '',
        timestamp: new Date().toISOString(),
      };
      return throwError(() => erroBackend);
    }
  
    // 🔹 Demais casos (fallback)
    const erroBackend: ErrorMessage = {
      status: error.status,
      error: error.error?.error || 'Erro desconhecido',
      message: error.error?.message || 'Erro ao processar requisição',
      path: error.error?.path || error.url || '',
      timestamp: error.error?.timestamp || new Date().toISOString(),
    };
  
    return throwError(() => erroBackend);
  }
  
}
