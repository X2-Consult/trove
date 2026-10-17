import {inject, Injectable} from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {Observable} from 'rxjs';
import {API_CONFIG} from '../../../core/config/api-config';

export interface ConflictBook {
  bookId: number;
  libraryId: number;
  libraryName: string;
  title: string;
  seriesName: string | null;
  seriesNumber: number | null;
  goodreadsId: string | null;
  asin: string | null;
  isbn13: string | null;
  isbn10: string | null;
}

export interface IdentifierConflictGroup {
  identifier: string;
  libraryId: number;
  libraryName: string;
  books: ConflictBook[];
}

export type RepairGroupStatus = 'RESOLVED' | 'NO_OWNER' | 'AMBIGUOUS' | 'LOOKUP_FAILED';
export type RepairBookAction = 'KEEP' | 'CLEAR' | 'LOCKED';

export interface RepairPlanGroup {
  identifier: string;
  libraryId: number;
  libraryName: string;
  ownerTitle: string | null;
  status: RepairGroupStatus;
  books: {bookId: number; title: string; goodreadsId: string | null; action: RepairBookAction}[];
}

export interface RepairPlanStatus {
  running: boolean;
  checked: number;
  total: number;
  plan: {groups: RepairPlanGroup[]} | null;
  error: string | null;
}

@Injectable({
  providedIn: 'root'
})
export class IdentifierConflictsService {
  private readonly http = inject(HttpClient);
  private readonly url = `${API_CONFIG.BASE_URL}/api/v1/books/metadata/identifier-conflicts`;

  getConflicts(): Observable<IdentifierConflictGroup[]> {
    return this.http.get<IdentifierConflictGroup[]>(this.url);
  }

  startRepairPlan(): Observable<RepairPlanStatus> {
    return this.http.post<RepairPlanStatus>(`${this.url}/repair-plan`, {});
  }

  getRepairPlan(): Observable<RepairPlanStatus> {
    return this.http.get<RepairPlanStatus>(`${this.url}/repair-plan`);
  }

  repair(clears: {bookId: number; identifier: string}[]): Observable<number[]> {
    return this.http.post<number[]>(`${this.url}/repair`, {clears});
  }
}
