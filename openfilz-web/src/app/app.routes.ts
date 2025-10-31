import {Routes} from '@angular/router';
import {DashboardComponent} from './components/dashboard/dashboard.component';
import {FileExplorerComponent} from './components/file-explorer/file-explorer.component';
import {WipComponent} from "./components/wip/wip";

// Since we're using standalone components, we need to import them directly in routes
export const routes: Routes = [
  { path: '', redirectTo: '/dashboard', pathMatch: 'full' }, // Set dashboard as home page
  { path: 'dashboard', component: DashboardComponent },
  { path: 'my-folder', component: FileExplorerComponent },
  { path: 'recycle-bin', component: WipComponent },
  { path: 'favorites', component: WipComponent },
  //{ path: 'shared-files', component: WipComponent },
  { path: 'settings', component: WipComponent },
  { path: '**', redirectTo: '/dashboard' } // Wildcard route for undefined paths
];