import { Routes } from '@angular/router';
import { DashboardComponent } from './components/dashboard/dashboard.component';
import { FileExplorerComponent } from './components/file-explorer/file-explorer.component';
import {FavoritesComponent} from './pages/favorites/favorites.component';
import {RecycleBinComponent} from './pages/recycle-bin/recycle-bin.component';
import { WipComponent } from "./components/wip/wip";
import { SearchResultsComponent } from './components/search-results/search-results.component';

// Since we're using standalone components, we need to import them directly in routes
export const routes: Routes = [
  { path: '', redirectTo: '/dashboard', pathMatch: 'full' }, // Set dashboard as home page
  { path: 'dashboard', component: DashboardComponent },
  { path: 'my-folder', component: FileExplorerComponent },
  { path: 'search', component: SearchResultsComponent },
  { path: 'recycle-bin', component: RecycleBinComponent },
  { path: 'favorites', component: FavoritesComponent },
  //{ path: 'shared-files', component: WipComponent },
  { path: 'settings', component: WipComponent },
  { path: '**', redirectTo: '/dashboard' } // Wildcard route for undefined paths
];