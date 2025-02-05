import React from "react";
import "./home.css";

import {Route} from "@wonder/core-fe";
import {useLocation, Switch} from "react-router";
import {Ops} from "./ops";
import {EndUser} from "./enduser";
import {Home} from "./home";
const Main = () => {
    const location = useLocation();
    const isRootPath = location.pathname === "/";

    return (
        <div className={isRootPath ? "center-container" : ""}>
            <Switch location={location}>
                <Route exact key="/" path="/" component={() => <Home />} />
                <Route path="/ops" component={Ops}></Route>
                <Route path="/enduser" component={EndUser}></Route>
            </Switch>
        </div>
    );
};
export default Main;
