import {Injectable} from '@angular/core';
import {BehaviorSubject, Subject} from 'rxjs';
import {ElementInfo} from '../models/document.models';

@Injectable({
  providedIn: 'root'
})
export class BreadcrumbService {
  private breadcrumbsSource = new BehaviorSubject<ElementInfo[]>([]);
  currentBreadcrumbs = this.breadcrumbsSource.asObservable();

  private navigationSource = new Subject<ElementInfo | null>();
  navigation$ = this.navigationSource.asObservable();

  updateBreadcrumbs(breadcrumbs: ElementInfo[]) {
    this.breadcrumbsSource.next(breadcrumbs);
  }

  navigateTo(folder: ElementInfo | null) {
    this.navigationSource.next(folder);
  }
}