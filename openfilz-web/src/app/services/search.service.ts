import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { BehaviorSubject, Observable } from 'rxjs';
import { environment } from "../../environments/environment";
import { DocumentSearchResult, Suggestion, SearchFilters, FilterInput } from "../models/document.models";
import { DocumentApiService } from "./document-api.service";

@Injectable({
  providedIn: 'root'
})
export class SearchService {

  private readonly suggestionsUrl = environment.apiURL + '/suggestions'; // Your backend endpoint

  private filtersSubject = new BehaviorSubject<SearchFilters>({});
  public filters$ = this.filtersSubject.asObservable();

  constructor(private http: HttpClient, private documentApi: DocumentApiService) { }

  updateFilters(filters: SearchFilters) {
    this.filtersSubject.next(filters);
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
    return this.documentApi.searchDocuments(query, currentFilters, null);
  }
}