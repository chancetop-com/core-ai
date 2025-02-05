import React from "react";
import {Button, Flex} from "antd";
import {Link} from "react-router-dom";

export const Home = () => {
    return (
        <Flex gap={100}>
            <Button>
                <Link to="/ops">Ops</Link>
            </Button>
            <Button>
                <Link to="/enduser">Enduser</Link>
            </Button>
        </Flex>
    );
};
