import { Routes } from '@angular/router';
import { DashboardComponent } from './components/dashboard/dashboard.component';
import { FileExplorerComponent } from './components/file-explorer/file-explorer.component';
import { FavoritesComponent } from './pages/favorites/favorites.component';
import { RecycleBinComponent } from './pages/recycle-bin/recycle-bin.component';
import { WipComponent } from "./components/wip/wip";
import { SettingsComponent } from './pages/settings/settings.component';
import { SearchResultsComponent } from './components/search-results/search-results.component';
import { authGuard } from './guards/auth.guard';

// Since we're using standalone components, we need to import them directly in routes
export const routes: Routes = [
  { path: '', redirectTo: '/dashboard', pathMatch: 'full' }, // Set dashboard as home page
  { path: 'dashboard', component: DashboardComponent, canActivate: [authGuard] },
  { path: 'my-folder', component: FileExplorerComponent, canActivate: [authGuard] },
  { path: 'search', component: SearchResultsComponent, canActivate: [authGuard] },
  { path: 'recycle-bin', component: RecycleBinComponent, canActivate: [authGuard] },
  { path: 'favorites', component: FavoritesComponent, canActivate: [authGuard] },
  //{ path: 'shared-files', component: WipComponent },
  { path: 'settings', component: SettingsComponent, canActivate: [authGuard] },
  { path: '**', redirectTo: '/dashboard' } // Wildcard route for undefined paths
];