import {inject, Injectable} from '@angular/core';
import {HttpClient, HttpParams} from '@angular/common/http';
import {Observable} from 'rxjs';
import {API_CONFIG} from '../../../core/config/api-config';

export interface ServerLogFile {
  name: string;
  sizeBytes: number;
  modified: string;
  current: boolean;
}

export interface ServerLogEntry {
  timestamp: string | null;
  level: 'TRACE' | 'DEBUG' | 'INFO' | 'WARN' | 'ERROR';
  thread: string;
  logger: string;
  message: string;
  detail: string | null;
}

export interface ServerLogPage {
  file: string;
  entries: ServerLogEntry[];
  matched: number;
  truncated: boolean;
}

@Injectable({
  providedIn: 'root'
})
export class ServerLogService {
  private readonly http = inject(HttpClient);
  private readonly url = `${API_CONFIG.BASE_URL}/api/v1/server-logs`;

  getFiles(): Observable<ServerLogFile[]> {
    return this.http.get<ServerLogFile[]>(`${this.url}/files`);
  }

  read(file: string | null, level: string | null, query: string | null, limit: number): Observable<ServerLogPage> {
    let params = new HttpParams().set('limit', limit.toString());
    if (file) {
      params = params.set('file', file);
    }
    if (level) {
      params = params.set('level', level);
    }
    if (query) {
      params = params.set('q', query);
    }
    return this.http.get<ServerLogPage>(this.url, {params});
  }

  download(file: string | null): Observable<Blob> {
    const params = file ? new HttpParams().set('file', file) : undefined;
    return this.http.get(`${this.url}/download`, {params, responseType: 'blob'});
  }
}
