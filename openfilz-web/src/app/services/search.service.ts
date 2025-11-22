import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { BehaviorSubject, Observable } from 'rxjs';
import { environment } from "../../environments/environment";
import { DocumentSearchResult, Suggestion, SearchFilters, FilterInput } from "../models/document.models";
import { DocumentApiService } from "./document-api.service";
import { UserPreferencesService } from './user-preferences.service';

@Injectable({
  providedIn: 'root'
})
export class SearchService {

  private readonly suggestionsUrl = environment.apiURL + '/suggestions'; // Your backend endpoint

  private filtersSubject = new BehaviorSubject<SearchFilters>({});
  public filters$ = this.filtersSubject.asObservable();

  private sortSubject = new BehaviorSubject<{ sortBy: string, sortOrder: 'ASC' | 'DESC' }>({ sortBy: 'name', sortOrder: 'ASC' });
  public sort$ = this.sortSubject.asObservable();

  constructor(
    private http: HttpClient, 
    private documentApi: DocumentApiService,
    private userPreferencesService: UserPreferencesService
  ) {
    const prefs = this.userPreferencesService.getPreferences();
    this.sortSubject.next({ sortBy: prefs.sortBy, sortOrder: prefs.sortOrder });
  }

  updateFilters(filters: SearchFilters) {
    this.filtersSubject.next(filters);
  }

  updateSort(sortBy: string, sortOrder: 'ASC' | 'DESC') {
    this.sortSubject.next({ sortBy, sortOrder });
    this.userPreferencesService.setSort(sortBy, sortOrder);
  }

  getSuggestions(query: string): Observable<Suggestion[]> {
    console.log('getSuggestions for query: ' + query);
    if (!query.trim()) {
      // If the query is empty, return an empty array immediately
      return new Observable(observer => observer.next([]));
    }

    const params = new HttpParams().set('q', query);
    return this.http.get<Suggestion[]>(this.suggestionsUrl, { params });
  }

  searchDocuments(query: string): Observable<DocumentSearchResult> {
    const currentFilters = this.filtersSubject.value;
    const currentSort = this.sortSubject.value;
    const sortInput = {
      field: currentSort.sortBy,
      order: currentSort.sortOrder
    };
    return this.documentApi.searchDocuments(query, currentFilters, sortInput);
  }
}