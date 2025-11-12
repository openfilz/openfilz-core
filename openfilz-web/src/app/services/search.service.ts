import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import {environment} from "../../environments/environment";
import {Suggestion} from "../models/document.models";

@Injectable({
  providedIn: 'root'
})
export class SearchService {

  private readonly suggestionsUrl = environment.apiURL + '/suggestions'; // Your backend endpoint

  constructor(private http: HttpClient) { }

  getSuggestions(query: string): Observable<Suggestion[]> {
      console.log('getSuggestions for query: ' + query);
    if (!query.trim()) {
      // If the query is empty, return an empty array immediately
      return new Observable(observer => observer.next([]));
    }

    const params = new HttpParams().set('q', query);
    return this.http.get<Suggestion[]>(this.suggestionsUrl, { params });
  }
}